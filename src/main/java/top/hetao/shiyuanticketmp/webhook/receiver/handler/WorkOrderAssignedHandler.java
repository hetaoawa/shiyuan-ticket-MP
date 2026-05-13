package top.hetao.shiyuanticketmp.webhook.receiver.handler;

import com.fasterxml.jackson.databind.JsonNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 工单派发事件处理器。
 *
 * <p>处理 WORK_ORDER.ASSIGNED 事件，可扩展为：
 * <ul>
 *   <li>通知处理人有新工单</li>
 *   <li>更新工单统计缓存</li>
 *   <li>记录操作日志</li>
 * </ul>
 */
@Component
public class WorkOrderAssignedHandler implements WebhookEventHandler {

    private static final Logger log = LoggerFactory.getLogger(WorkOrderAssignedHandler.class);

    @Override
    public String eventType() {
        return "WORK_ORDER.ASSIGNED";
    }

    @Override
    public void handle(JsonNode event) {
        JsonNode data = event.get("data");
        Long workOrderId = data.get("workOrderId").asLong();
        Long assigneeId = data.has("assigneeId") ? data.get("assigneeId").asLong() : null;

        log.info("[工单派发] workOrderId={} assigneeId={}", workOrderId, assigneeId);

        // TODO: 通知处理人（站内信/邮件/短信）
        // TODO: 更新工单统计缓存
    }
}
