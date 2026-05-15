package top.hetao.shiyuanticketmp.workorder.listener;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import top.hetao.shiyuanticketmp.webhook.sender.ChannelTarget;
import top.hetao.shiyuanticketmp.webhook.sender.WebhookMessageAggregator;
import top.hetao.shiyuanticketmp.workorder.entity.WorkOrder;
import top.hetao.shiyuanticketmp.workorder.event.WorkOrderCommentEvent;
import top.hetao.shiyuanticketmp.workorder.event.WorkOrderEvent;
import top.hetao.shiyuanticketmp.workorder.event.WorkOrderStateChangedEvent;

import java.util.Map;
import java.util.UUID;

/**
 * 工单事件监听器 — WebHook 聚合投递。
 *
 * <p><b>推送规则：</b>
 * <ul>
 *   <li><b>货主侧</b>：仅推送工单创建成功（外部提交）、工单有新留言、工单被完结</li>
 *   <li><b>云仓侧</b>：仅推送工单被派发给云仓（不论是否外部提交）</li>
 * </ul>
 */
@Component
public class WorkOrderWebhookListener {

    private static final Logger log = LoggerFactory.getLogger(WorkOrderWebhookListener.class);

    private final WebhookMessageAggregator aggregator;

    public WorkOrderWebhookListener(WebhookMessageAggregator aggregator) {
        this.aggregator = aggregator;
    }

    /**
     * 工单状态变更事件处理。
     */
    @EventListener
    @Async("webhookExecutor")
    public void onWorkOrderStateChanged(WorkOrderStateChangedEvent event) {
        WorkOrder order = event.getWorkOrder();
        String action = event.getAction();

        ChannelTarget target = resolveChannelTarget(action, order);
        if (target == null) {
            log.debug("[WebHook] 事件无需推送 action={} orderId={}", action, order.getId());
            return;
        }

        WorkOrderEvent payload = WorkOrderEvent.of(order, event.getExtra());
        payload.setTargetChannels(target);

        log.info("[WebHook] 事件入队聚合器 action={} orderId={} target={}", action, order.getId(), target);
        aggregator.submit(payload);
    }

    /**
     * 工单评论事件处理。
     *
     * <p>评论事件仅推送给货主侧（外部提交的工单）。
     */
    @EventListener
    @Async("webhookExecutor")
    public void onWorkOrderComment(WorkOrderCommentEvent event) {
        WorkOrder order = event.getWorkOrder();
        if (order == null) {
            return;
        }

        // 仅外部提交的工单才推送评论给货主
        if (order.getConversationId() == null || order.getConversationId().isBlank()) {
            log.debug("[WebHook] 非外部提交工单的评论不推送 orderId={}", order.getId());
            return;
        }

        WorkOrderEvent payload = new WorkOrderEvent();
        payload.setEventId(UUID.randomUUID().toString());
        payload.setWorkOrderId(order.getId());
        payload.setTitle(order.getTitle());
        payload.setType(order.getType());
        payload.setTrackingNo(order.getTrackingNo());
        payload.setTargetAddress(order.getTargetAddress());
        payload.setStatus("COMMENT");
        payload.setAssigneeId(order.getAssigneeId());
        payload.setSubmitterId(order.getSubmitterId());
        payload.setCreatedAt(order.getCreatedAt());
        payload.setConversationId(order.getConversationId());
        payload.setSenderStaffId(order.getSenderStaffId());
        payload.setOccurredAt(java.time.LocalDateTime.now());
        payload.setExtra(Map.of(
                "commentContent", event.getComment().getContent(),
                "commenterId", event.getComment().getCommenterId()
        ));
        payload.setTargetChannels(ChannelTarget.CARGO_OWNER);

        log.info("[WebHook] 评论事件入队 orderId={} target=CARGO_OWNER", order.getId());
        aggregator.submit(payload);
    }

    /**
     * 根据动作和工单信息判断目标通道。
     *
     * @return 目标通道，null 表示不推送
     */
    private ChannelTarget resolveChannelTarget(String action, WorkOrder order) {
        return switch (action) {
            case "CREATE" -> {
                // 工单创建：仅外部提交（有 conversationId）的工单推送给货主
                if (order.getConversationId() != null && !order.getConversationId().isBlank()) {
                    yield ChannelTarget.CARGO_OWNER;
                }
                yield null;
            }
            case "ASSIGN" -> {
                // 工单派发：仅推送给云仓侧
                yield ChannelTarget.DINGTALK;
            }
            case "CLOSE" -> {
                // 工单完结：仅推送给货主侧（外部提交的工单）
                if (order.getConversationId() != null && !order.getConversationId().isBlank()) {
                    yield ChannelTarget.CARGO_OWNER;
                }
                yield null;
            }
            // REJECT、RESUBMIT、FORCE_REJECT 不推送
            default -> null;
        };
    }
}
