package top.hetao.shiyuanticketmp.webhook.deadletter;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;
import top.hetao.shiyuanticketmp.common.entity.BaseEntity;
import top.hetao.shiyuanticketmp.webhook.deadletter.enums.DeadLetterStatus;

import java.time.LocalDateTime;

/**
 * WebHook 死信记录实体，对应数据库表 {@code webhook_dead_letter}。
 *
 * <p>当一次 WebHook 投递经过全部重试仍然失败时，将本次投递的完整上下文
 * 持久化到此表，供管理员通过后台页面查看并手动补偿。
 *
 * <p><b>管理员处理流程：</b>
 * <ol>
 *   <li>后台列表页展示所有 {@code PENDING} 状态的死信记录</li>
 *   <li>管理员点击「重新投递」→ 调用 {@link WebhookDeadLetterService#retry}</li>
 *   <li>投递成功后状态更新为 {@code RESOLVED}；确认无需处理可标记 {@code IGNORED}</li>
 * </ol>
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("webhook_dead_letter")
public class WebhookDeadLetterRecord extends BaseEntity {

    /** 原始事件 ID，与投递时的 Header X-Event-Id 一致，便于全链路追踪 */
    private String eventId;

    /** 事件类型，如 WORK_ORDER.CLOSED */
    private String eventType;

    /** 原始目标 URL */
    private String targetUrl;

    /**
     * 原始请求体 JSON（TEXT 类型存储）。
     * 管理员可直接查看内容确认是否需要补偿，补偿时直接重投此字段内容。
     */
    private String payload;

    /** 最后一次失败的错误描述（HTTP 状态码或异常消息，截断至 1024 字符） */
    private String lastError;

    /** 累计已尝试次数（含首次 + 全部重试轮次） */
    private int attempts;

    /** 管理员处理时间（RESOLVED / IGNORED 后写入） */
    private LocalDateTime resolvedAt;

    /** 处理人（管理员用户名），用于操作审计 */
    private String resolvedBy;

    /** 当前状态 */
    private DeadLetterStatus status;

    /**
     * 工厂方法，由 {@link top.hetao.shiyuanticketmp.webhook.WebhookDispatcher} 调用，
     * 在全量重试耗尽后构造死信记录落库。
     */
    public static WebhookDeadLetterRecord of(String eventId, String eventType,
                                             String targetUrl, String payload,
                                             String lastError, int attempts) {
        WebhookDeadLetterRecord r = new WebhookDeadLetterRecord();
        r.setEventId(eventId);
        r.setEventType(eventType);
        r.setTargetUrl(targetUrl);
        r.setPayload(payload);
        r.setLastError(lastError);
        r.setAttempts(attempts);
        r.setStatus(DeadLetterStatus.PENDING);
        return r;
    }

    /** 管理员标记为已补偿处理 */
    public void markResolved(String operator) {
        this.status     = DeadLetterStatus.RESOLVED;
        this.resolvedAt = LocalDateTime.now();
        this.resolvedBy = operator;
    }

    /** 管理员确认忽略，不再重试 */
    public void markIgnored(String operator) {
        this.status     = DeadLetterStatus.IGNORED;
        this.resolvedAt = LocalDateTime.now();
        this.resolvedBy = operator;
    }
}
