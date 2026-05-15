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

    private final WorkOrder workOrder;
    private final WorkOrderComment comment;

    public WorkOrderCommentEvent(Object source, WorkOrder workOrder, WorkOrderComment comment) {
        super(source);
        this.workOrder = workOrder;
        this.comment = comment;
    }

    public WorkOrder getWorkOrder() {
        return workOrder;
    }

    public WorkOrderComment getComment() {
        return comment;
    }
}
