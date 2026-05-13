package top.hetao.shiyuanticketmp.webhook.receiver.handler;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * WebHook 事件处理器接口。
 *
 * <p>每种 eventType 对应一个实现类，由 {@link WebhookEventRouter} 自动路由。
 * 实现类需注册为 Spring Bean（@Component），框架会自动收集并注册到路由器。
 *
 * <p>示例：
 * <pre>{@code
 * @Component
 * public class WorkOrderAssignedHandler implements WebhookEventHandler {
 *     @Override
 *     public String eventType() { return "WORK_ORDER.ASSIGNED"; }
 *
 *     @Override
 *     public void handle(JsonNode event) { ... }
 * }
 * }</pre>
 */
public interface WebhookEventHandler {

    /**
     * 该处理器负责的事件类型。
     *
     * <p>必须与 WebhookEnvelope.eventType 完全匹配，如 "WORK_ORDER.CLOSED"。
     *
     * @return 事件类型标识
     */
    String eventType();

    /**
     * 处理事件。
     *
     * <p>event 结构为 WebhookEnvelope 的 JSON：
     * <pre>
     * {
     *   "eventId": "uuid",
     *   "eventType": "WORK_ORDER.CLOSED",
     *   "timestamp": 1715500000,
     *   "data": { ... }  // 业务数据
     * }
     * </pre>
     *
     * @param event 解析后的 JSON 根节点
     */
    void handle(JsonNode event);
}
