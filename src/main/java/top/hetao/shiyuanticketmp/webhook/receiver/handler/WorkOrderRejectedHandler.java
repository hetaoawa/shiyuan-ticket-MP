package top.hetao.shiyuanticketmp.webhook.receiver.handler;

import com.fasterxml.jackson.databind.JsonNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 工单驳回事件处理器。
 *
 * <p>处理 WORK_ORDER.REJECTED 事件，可扩展为：
 * <ul>
 *   <li>通知货主工单被驳回及原因</li>
 *   <li>记录驳回统计</li>
 * </ul>
 */
@Component
public class WorkOrderRejectedHandler implements WebhookEventHandler {

    private static final Logger log = LoggerFactory.getLogger(WorkOrderRejectedHandler.class);

    @Override
    public String eventType() {
        return "WORK_ORDER.REJECTED";
    }

    @Override
    public void handle(JsonNode event) {
        JsonNode data = event.get("data");
        Long workOrderId = data.get("workOrderId").asLong();
        String title = data.has("title") ? data.get("title").asText() : "";

        log.info("[工单驳回] workOrderId={} title={}", workOrderId, title);

        // TODO: 通知货主工单被驳回及原因
        // TODO: 记录驳回统计
    }
}
