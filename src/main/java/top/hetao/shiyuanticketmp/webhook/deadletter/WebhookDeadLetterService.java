package top.hetao.shiyuanticketmp.webhook.deadletter;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import top.hetao.shiyuanticketmp.common.context.TenantContext;
import top.hetao.shiyuanticketmp.tenant.service.TenantLifecycleGuard;
import top.hetao.shiyuanticketmp.webhook.deadletter.enums.DeadLetterStatus;
import top.hetao.shiyuanticketmp.webhook.sender.CargoOwnerDispatcher;
import top.hetao.shiyuanticketmp.webhook.sender.DingTalkDispatcher;
import top.hetao.shiyuanticketmp.webhook.sender.DispatchResult;

import java.nio.charset.StandardCharsets;
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
    private final TenantLifecycleGuard tenantLifecycleGuard;

    private final ObjectProvider<DingTalkDispatcher> dingTalkDispatcherProvider;
    private final ObjectProvider<CargoOwnerDispatcher> cargoOwnerDispatcherProvider;

    public WebhookDeadLetterService(WebhookDeadLetterMapper mapper,
                                    ObjectProvider<DingTalkDispatcher> dingTalkDispatcherProvider,
                                    ObjectProvider<CargoOwnerDispatcher> cargoOwnerDispatcherProvider,
                                    TenantLifecycleGuard tenantLifecycleGuard) {
        this.mapper = mapper;
        this.dingTalkDispatcherProvider = dingTalkDispatcherProvider;
        this.cargoOwnerDispatcherProvider = cargoOwnerDispatcherProvider;
        this.tenantLifecycleGuard = tenantLifecycleGuard;
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
        if (record == null || record.getTenantId() == null) {
            throw new IllegalArgumentException("Dead-letter tenant id is required");
        }
        try (TenantContext.Scope ignored = TenantContext.useTenant(record.getTenantId())) {
            tenantLifecycleGuard.lockTenantForDerivedWrite(record.getTenantId());
            if (mapper.insert(record) != 1) {
                throw new IllegalStateException("Dead-letter insert affected an unexpected row count");
            }
        }
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
     * <p>补偿投递是同步的，并按死信记录中的稳定通道代码使用当前通道配置：
     * <ul>
     *   <li>投递成功 → {@link WebhookDeadLetterRecord#markResolved} → 更新数据库状态</li>
     *   <li>投递再次失败 → 保持 PENDING，不生成重复死信记录</li>
     * </ul>
     *
     * @param deadLetterId 死信记录主键
     * @param operator     操作管理员的用户名，用于操作审计
     */
    @Transactional
    public void retry(Long deadLetterId, String operator) {
        tenantLifecycleGuard.lockWritableTenant(TenantContext.requireTenantId());
        WebhookDeadLetterRecord record = mapper.selectByIdForUpdate(deadLetterId);
        if (record == null) {
            throw new IllegalArgumentException("死信记录不存在: " + deadLetterId);
        }

        if (record.getStatus() != DeadLetterStatus.PENDING) {
            throw new IllegalStateException("该死信记录已处理，状态: " + record.getStatus());
        }

        log.info("[死信] 管理员 {} 触发手动补偿 eventId={} eventType={}",
                operator, record.getEventId(), record.getEventType());

        DispatchResult result = retryThroughRecordedChannel(record);
        if (!result.success()) {
            log.warn("[死信] 重投失败，保持 PENDING eventId={} channel={} status={} error={}",
                    record.getEventId(), record.getChannel(), result.statusCode(), result.message());
            throw new IllegalStateException("死信重投失败: " + result.message());
        }

        record.markResolved(operator);
        mapper.updateById(record);
        log.info("[死信] 重投成功并标记 RESOLVED eventId={} channel={} status={}",
                record.getEventId(), record.getChannel(), result.statusCode());
    }

    private DispatchResult retryThroughRecordedChannel(WebhookDeadLetterRecord record) {
        String channel = record.getChannel();
        if (channel == null || channel.isBlank()) {
            throw new IllegalStateException("历史死信缺少投递通道，无法安全重试");
        }
        byte[] payload = record.getPayload().getBytes(StandardCharsets.UTF_8);
        try (TenantContext.Scope ignored = TenantContext.useTenant(record.getTenantId())) {
            return switch (channel) {
                case DingTalkDispatcher.CHANNEL_CODE -> dingTalkDispatcherProvider.getObject()
                        .retryRaw(record.getEventType(), record.getEventId(), payload);
                case CargoOwnerDispatcher.CHANNEL_CODE -> cargoOwnerDispatcherProvider.getObject()
                        .retryRaw(record.getEventType(), record.getEventId(), payload);
                default -> throw new IllegalStateException("未知死信投递通道: " + channel);
            };
        }
    }

    /**
     * 管理员确认忽略该死信（业务已通过其他途径补偿，无需重推）。
     *
     * @param deadLetterId 死信记录主键
     * @param operator     操作管理员的用户名
     */
    @Transactional
    public void ignore(Long deadLetterId, String operator) {
        tenantLifecycleGuard.lockWritableTenant(TenantContext.requireTenantId());
        WebhookDeadLetterRecord record = mapper.selectByIdForUpdate(deadLetterId);
        if (record == null) {
            throw new IllegalArgumentException("死信记录不存在: " + deadLetterId);
        }
        if (record.getStatus() != DeadLetterStatus.PENDING) {
            throw new IllegalStateException("Dead letter has already been handled: " + record.getStatus());
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
