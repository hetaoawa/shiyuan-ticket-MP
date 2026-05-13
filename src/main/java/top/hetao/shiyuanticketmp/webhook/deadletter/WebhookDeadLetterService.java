package top.hetao.shiyuanticketmp.webhook.deadletter;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import top.hetao.shiyuanticketmp.webhook.WebhookDispatcher;
import top.hetao.shiyuanticketmp.webhook.deadletter.enums.DeadLetterStatus;

import java.util.List;

/**
 * 死信补偿服务
 *
 * <p>提供三类能力：
 * <ol>
 *   <li><b>写入</b>：由 {@link top.hetao.shiyuanticketmp.webhook.WebhookDispatcher}
 *       在全量重试耗尽后调用 {@link #save} 落库</li>
 *   <li><b>查询</b>：管理后台 Controller 调用 {@link #listPending} 分页展示待处理死信</li>
 *   <li><b>补偿</b>：管理员触发 {@link #retry} 手动重新投递；或 {@link #ignore} 忽略</li>
 * </ol>
 */
@Service
public class WebhookDeadLetterService {

    private static final Logger log = LoggerFactory.getLogger(WebhookDeadLetterService.class);

    private final WebhookDeadLetterMapper mapper;

    /**
     * 补偿投递时复用 Dispatcher，避免重复实现 HTTP + 重试逻辑。
     * 使用字段注入避免与 WebhookDispatcher 构造器形成循环依赖。
     */
    private final top.hetao.shiyuanticketmp.webhook.WebhookDispatcher dispatcher;

    public WebhookDeadLetterService(WebhookDeadLetterMapper mapper,
                                    @Lazy WebhookDispatcher dispatcher) {
        this.mapper     = mapper;
        this.dispatcher = dispatcher;
    }

    // ----------------------------------------------------------------
    // 写入死信
    // ----------------------------------------------------------------

    /**
     * 持久化死信记录。
     *
     * <p>使用 {@code REQUIRES_NEW} 独立新事务：即使外层业务事务回滚（或根本没有事务），
     * 死信记录也能独立提交落库，确保不因业务异常导致死信丢失。
     *
     * @param record 已由 Dispatcher 构造好的死信记录
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void save(WebhookDeadLetterRecord record) {
        mapper.insert(record);
        log.warn("[死信] 已落库 eventId={} eventType={} attempts={}",
                record.getEventId(), record.getEventType(), record.getAttempts());
    }

    // ----------------------------------------------------------------
    // 管理员查询
    // ----------------------------------------------------------------

    /**
     * 分页查询待处理死信列表，供后台管理页面展示。
     *
     * @param page     页码（从 1 开始）
     * @param pageSize 每页条数
     * @return 分页结果封装
     */
    @Transactional(readOnly = true)
    public DeadLetterPageResult listPending(int page, int pageSize) {
        LambdaQueryWrapper<WebhookDeadLetterRecord> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(WebhookDeadLetterRecord::getStatus, DeadLetterStatus.PENDING)
               .orderByDesc(WebhookDeadLetterRecord::getCreatedAt);

        IPage<WebhookDeadLetterRecord> pageResult = mapper.selectPage(
                new Page<>(page, pageSize), wrapper);

        return new DeadLetterPageResult(
                pageResult.getRecords(),
                pageResult.getTotal(),
                page,
                pageSize);
    }

    // ----------------------------------------------------------------
    // 手动补偿投递
    // ----------------------------------------------------------------

    /**
     * 管理员手动触发重新投递。
     *
     * <p><b>event_id 不变原则：</b>补偿时保持原 {@code eventId} 不变并重投到原 Header，
     * 若上次实际已送达（网络超时导致误判失败），接收方将因 {@code eventId} 重复而幂等忽略，
     * 不会产生重复处理。
     *
     * <p>补偿投递结果：
     * <ul>
     *   <li>投递成功 → {@link WebhookDeadLetterRecord#markResolved} → 更新数据库状态</li>
     *   <li>投递再次失败 → 由 Dispatcher 内部重试，全量失败后重新写入新死信记录</li>
     * </ul>
     *
     * @param deadLetterId 死信记录主键
     * @param operator     操作管理员的用户名，用于操作审计
     */
    @Transactional
    public void retry(Long deadLetterId, String operator) {
        WebhookDeadLetterRecord record = mapper.selectById(deadLetterId);
        if (record == null) {
            throw new IllegalArgumentException("死信记录不存在: " + deadLetterId);
        }

        if (record.getStatus() != DeadLetterStatus.PENDING) {
            throw new IllegalStateException("该死信记录已处理，状态: " + record.getStatus());
        }

        log.info("[死信] 管理员 {} 触发手动补偿 eventId={} eventType={}",
                operator, record.getEventId(), record.getEventType());

        // 保持原 eventId，原始 payload 字节直接重投，跳过序列化
        dispatcher.dispatchRaw(
                record.getTargetUrl(),
                record.getEventType(),
                record.getEventId(),   // ← 保持原 eventId 不变，接收方幂等
                record.getPayload().getBytes(java.nio.charset.StandardCharsets.UTF_8)
        );

        record.markResolved(operator);
        mapper.updateById(record);
    }

    /**
     * 管理员确认忽略该死信（业务已通过其他途径补偿，无需重推）。
     *
     * @param deadLetterId 死信记录主键
     * @param operator     操作管理员的用户名
     */
    @Transactional
    public void ignore(Long deadLetterId, String operator) {
        WebhookDeadLetterRecord record = mapper.selectById(deadLetterId);
        if (record == null) {
            throw new IllegalArgumentException("死信记录不存在: " + deadLetterId);
        }
        record.markIgnored(operator);
        mapper.updateById(record);
        log.info("[死信] 管理员 {} 标记忽略 eventId={}", operator, record.getEventId());
    }

    // ----------------------------------------------------------------
    // 分页结果 DTO
    // ----------------------------------------------------------------

    /**
     * 死信分页结果，供 Controller 序列化为 JSON 返回前端。
     */
    public record DeadLetterPageResult(
            List<WebhookDeadLetterRecord> records,
            long total,
            int  page,
            int  pageSize
    ) {}
}
