package top.hetao.shiyuanticketmp.webhook;

import java.time.Instant;

/**
 * WebHook 统一请求体信封
 *
 * <p>无论何种事件类型，发送给接收方的 JSON Body 始终遵循此结构，
 * 接收方用统一的反序列化逻辑解析外层字段，再按 {@code eventType} 路由到具体处理器。
 *
 * <pre>
 * {
 *   "eventId"   : "550e8400-e29b-41d4-a716-446655440000",  // 与 Header X-Event-Id 完全一致
 *   "eventType" : "WORK_ORDER.CLOSED",
 *   "timestamp" : 1713000000,
 *   "data"      : { ...业务 payload... }
 * }
 * </pre>
 *
 * <p><b>event_id 一致性保证：</b>
 * {@code eventId} 由 {@link WebhookDispatcher} 在入口处统一生成，
 * 同步写入 HTTP 请求头（{@code X-Event-Id}）与本信封的 {@code eventId} 字段，
 * 两处值恒相等，接收方可任选一处读取用于幂等去重。
 */
public class WebhookEnvelope {

    /** 全局唯一事件 ID，与请求头 X-Event-Id 保持完全一致 */
    private final String eventId;

    /** 事件类型，如 WORK_ORDER.ASSIGNED / WORK_ORDER.CLOSED */
    private final String eventType;

    /** 事件产生的 Unix 时间戳（秒），接收方可用于防重放攻击（5 分钟窗口） */
    private final long timestamp;

    /** 实际业务数据，序列化后嵌入 data 字段 */
    private final Object data;

    private WebhookEnvelope(String eventId, String eventType, Object data) {
        this.eventId   = eventId;
        this.eventType = eventType;
        this.timestamp = Instant.now().getEpochSecond();
        this.data      = data;
    }

    /**
     * 工厂方法，由 {@link WebhookDispatcher} 调用，统一创建信封对象。
     *
     * @param eventId   调用方已生成的唯一 ID，将同步写入请求头
     * @param eventType 事件类型标识
     * @param data      业务 payload 对象
     */
    public static WebhookEnvelope wrap(String eventId, String eventType, Object data) {
        return new WebhookEnvelope(eventId, eventType, data);
    }

    public String getEventId()   { return eventId;   }
    public String getEventType() { return eventType; }
    public long   getTimestamp() { return timestamp; }
    public Object getData()      { return data;      }
}
