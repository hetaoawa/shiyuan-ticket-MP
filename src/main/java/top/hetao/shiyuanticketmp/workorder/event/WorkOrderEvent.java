package top.hetao.shiyuanticketmp.workorder.event;

import lombok.Data;
import top.hetao.shiyuanticketmp.workorder.entity.WorkOrder;

import java.time.LocalDateTime;
import java.util.Map;
import java.util.UUID;

/**
 * WebHook 事件业务 payload
 *
 * <p>字段设计原则：接收方不应因缺少信息而被迫回调查询，
 * 因此 payload 包含处理该事件所需的全部核心字段快照。
 *
 * <p><b>注意：</b>{@code eventId} 由 {@link top.hetao.shiyuanticketmp.webhook.WebhookDispatcher}
 * 在入口统一生成后通过 {@link #setEventId} 注入，本类不自行生成，
 * 确保 Header 与 Body 中的 eventId 严格一致。
 */
@Data
public class WorkOrderEvent {

    /**
     * 全局唯一事件 ID。
     * 由 WebhookDispatcher 生成后注入，与 HTTP Header {@code X-Event-Id} 完全一致。
     */
    private String eventId;

    /** 工单主键 */
    private Long workOrderId;

    /** 触发事件时的工单状态快照 */
    private String status;

    /** 工单标题快照 */
    private String title;

    /** 处理人 ID */
    private Long assigneeId;

    /** 事件发生时间 */
    private LocalDateTime occurredAt;

    /**
     * 各事件特有扩展字段。
     * 例如：关单事件包含 {@code resolution}，驳回事件包含 {@code reason}
     */
    private Map<String, Object> extra;

    /**
     * 工厂方法，从工单实体构建事件对象。
     * eventId 留空，由 WebhookDispatcher 后续注入。
     *
     * @param order 已持久化的工单实体
     * @param extra 当前事件特有的扩展字段
     */
    public static WorkOrderEvent of(WorkOrder order, Map<String, Object> extra) {
        WorkOrderEvent e = new WorkOrderEvent();
        // eventId 暂不赋值，由 Dispatcher 统一生成后通过 setEventId 注入
        e.setWorkOrderId(order.getId());
        e.setStatus(order.getStatus().name());
        e.setTitle(order.getTitle());
        e.setAssigneeId(order.getAssigneeId());
        e.setOccurredAt(LocalDateTime.now());
        e.setExtra(extra);
        return e;
    }
}
