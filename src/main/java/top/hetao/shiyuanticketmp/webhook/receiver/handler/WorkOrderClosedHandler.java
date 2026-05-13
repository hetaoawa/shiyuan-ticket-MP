package top.hetao.shiyuanticketmp.webhook.receiver.handler;

import com.fasterxml.jackson.databind.JsonNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 工单关闭事件处理器。
 *
 * <p>处理 WORK_ORDER.CLOSED 事件，可扩展为：
 * <ul>
 *   <li>通知货主工单已处理完成</li>
 *   <li>触发满意度调查</li>
 *   <li>更新统计报表</li>
 * </ul>
 */
@Component
public class WorkOrderClosedHandler implements WebhookEventHandler {

    private static final Logger log = LoggerFactory.getLogger(WorkOrderClosedHandler.class);

    @Override
    public String eventType() {
        return "WORK_ORDER.CLOSED";
    }

    @Override
    public void handle(JsonNode event) {
        JsonNode data = event.get("data");
        Long workOrderId = data.get("workOrderId").asLong();
        String title = data.has("title") ? data.get("title").asText() : "";

        log.info("[工单关闭] workOrderId={} title={}", workOrderId, title);

        // TODO: 通知货主工单已关闭
        // TODO: 触发满意度调查
        // TODO: 更新统计报表
    }
}
