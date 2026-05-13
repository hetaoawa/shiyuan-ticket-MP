package top.hetao.shiyuanticketmp.workorder.listener;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import top.hetao.shiyuanticketmp.webhook.WebhookDispatcher;
import top.hetao.shiyuanticketmp.workorder.entity.WorkOrder;
import top.hetao.shiyuanticketmp.workorder.event.WorkOrderEvent;
import top.hetao.shiyuanticketmp.workorder.event.WorkOrderStateChangedEvent;

import java.util.Map;

/**
 * 工单状态变更事件监听器 — WebHook 投递。
 *
 * <p>通过 Spring Event 解耦，Service 层不再直接依赖 WebhookDispatcher。
 * 事件在事务提交后异步执行投递（{@code @TransactionalEventListener(phase=AFTER_COMMIT)}）。
 */
@Component
public class WorkOrderWebhookListener {

    private static final Logger log = LoggerFactory.getLogger(WorkOrderWebhookListener.class);

    private static final Map<String, String> ACTION_TO_EVENT_TYPE = Map.of(
            "ASSIGN", "WORK_ORDER.ASSIGNED",
            "CLOSE",  "WORK_ORDER.CLOSED",
            "REJECT", "WORK_ORDER.REJECTED"
    );

    private final WebhookDispatcher webhookDispatcher;

    @Value("${webhook.url}")
    private String webhookUrl;

    public WorkOrderWebhookListener(WebhookDispatcher webhookDispatcher) {
        this.webhookDispatcher = webhookDispatcher;
    }

    @EventListener
    @Async("webhookExecutor")
    public void onWorkOrderStateChanged(WorkOrderStateChangedEvent event) {
        String eventType = ACTION_TO_EVENT_TYPE.get(event.getAction());
        if (eventType == null) {
            // CREATE 等动作不发 WebHook
            return;
        }

        WorkOrder order = event.getWorkOrder();
        WorkOrderEvent payload = WorkOrderEvent.of(order, event.getExtra());

        log.info("[WebHook] 投递事件 eventType={} orderId={}", eventType, order.getId());
        webhookDispatcher.dispatch(webhookUrl, eventType, payload);
    }
}
