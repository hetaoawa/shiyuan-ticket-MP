package top.hetao.shiyuanticketmp.workorder.event;

import lombok.Data;
import top.hetao.shiyuanticketmp.webhook.sender.ChannelTarget;
import top.hetao.shiyuanticketmp.workorder.entity.WorkOrder;
import top.hetao.shiyuanticketmp.workorder.enums.WorkOrderType;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Map;

/**
 * WebHook 事件业务 payload。
 *
 * <p>字段设计原则：接收方不应因缺少信息而被迫回调查询，
 * 因此 payload 包含处理该事件所需的全部核心字段快照。
 */
@Data
public class WorkOrderEvent {

    private static final DateTimeFormatter FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    /** 全局唯一事件 ID */
    private String eventId;

    /** 工单主键 */
    private Long workOrderId;

    /** 工单标题 */
    private String title;

    /** 工单类型 */
    private WorkOrderType type;

    /** 物流单号 */
    private String trackingNo;

    /** 目标地址 */
    private String targetAddress;

    /** 触发事件时的工单状态快照 */
    private String status;

    /** 处理人 ID */
    private Long assigneeId;

    /** 提交人 ID */
    private Long submitterId;

    /** 工单创建时间 */
    private LocalDateTime createdAt;

    /** 事件发生时间 */
    private LocalDateTime occurredAt;

    /** 各事件特有扩展字段（resolution / reason 等） */
    private Map<String, Object> extra;

    /** 货主侧群 ID */
    private String conversationId;

    /** 货主侧发送人 ID */
    private String senderStaffId;

    /** 目标投递通道（业务层根据规则设置） */
    private ChannelTarget targetChannels;

    /**
     * 工厂方法，从工单实体构建事件对象。
     */
    public static WorkOrderEvent of(WorkOrder order, Map<String, Object> extra) {
        WorkOrderEvent e = new WorkOrderEvent();
        e.setWorkOrderId(order.getId());
        e.setTitle(order.getTitle());
        e.setType(order.getType());
        e.setTrackingNo(order.getTrackingNo());
        e.setTargetAddress(order.getTargetAddress());
        e.setStatus(order.getStatus().name());
        e.setAssigneeId(order.getAssigneeId());
        e.setSubmitterId(order.getSubmitterId());
        e.setCreatedAt(order.getCreatedAt());
        e.setConversationId(order.getConversationId());
        e.setSenderStaffId(order.getSenderStaffId());
        e.setOccurredAt(LocalDateTime.now());
        e.setExtra(extra);
        return e;
    }

    /** 格式化创建时间为字符串 */
    public String getCreatedAtFormatted() {
        return createdAt != null ? createdAt.format(FMT) : "";
    }

    /** 格式化事件时间为字符串 */
    public String getOccurredAtFormatted() {
        return occurredAt != null ? occurredAt.format(FMT) : "";
    }
}
