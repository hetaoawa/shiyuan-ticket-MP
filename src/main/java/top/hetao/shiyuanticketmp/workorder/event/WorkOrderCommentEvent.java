package top.hetao.shiyuanticketmp.workorder.event;

import org.springframework.context.ApplicationEvent;
import top.hetao.shiyuanticketmp.workorder.comment.entity.WorkOrderComment;
import top.hetao.shiyuanticketmp.workorder.entity.WorkOrder;

/**
 * 工单评论事件。
 *
 * <p>发布时机：工单添加评论时。
 * 监听方：WebHook 投递（仅货主侧）。
 */
public class WorkOrderCommentEvent extends ApplicationEvent {

    private final Long tenantId;
    private final WorkOrder workOrder;
    private final WorkOrderComment comment;

    public WorkOrderCommentEvent(Object source, Long tenantId,
                                 WorkOrder workOrder, WorkOrderComment comment) {
        super(source);
        if (tenantId == null) {
            throw new IllegalArgumentException("tenantId is required for work-order comment events");
        }
        this.tenantId = tenantId;
        this.workOrder = workOrder;
        this.comment = comment;
    }

    public Long getTenantId() {
        return tenantId;
    }

    public WorkOrder getWorkOrder() {
        return workOrder;
    }

    public WorkOrderComment getComment() {
        return comment;
    }
}
