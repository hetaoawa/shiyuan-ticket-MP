package top.hetao.shiyuanticketmp.workorder.event;

import org.springframework.context.ApplicationEvent;
import top.hetao.shiyuanticketmp.workorder.entity.WorkOrder;
import top.hetao.shiyuanticketmp.workorder.enums.WorkOrderStatus;

import java.util.Map;

/**
 * 工单状态变更 Spring 事件。
 *
 * <p>发布时机：工单创建、派发、关闭、驳回时。
 * 监听方：WebHook 投递、审计日志、缓存清理等。
 *
 * <p>使用 Spring ApplicationEventPublisher 解耦业务核心逻辑与副作用。
 */
public class WorkOrderStateChangedEvent extends ApplicationEvent {

    private final Long tenantId;
    private final WorkOrder workOrder;
    private final WorkOrderStatus previousStatus;
    private final String action;
    private final Long operatorId;
    private final Map<String, Object> extra;

    public WorkOrderStateChangedEvent(Object source, Long tenantId, WorkOrder workOrder,
                                       WorkOrderStatus previousStatus, String action,
                                       Long operatorId, Map<String, Object> extra) {
        super(source);
        if (tenantId == null) {
            throw new IllegalArgumentException("tenantId is required for work-order events");
        }
        this.tenantId = tenantId;
        this.workOrder = workOrder;
        this.previousStatus = previousStatus;
        this.action = action;
        this.operatorId = operatorId;
        this.extra = extra;
    }

    public Long getTenantId() {
        return tenantId;
    }

    public WorkOrder getWorkOrder() {
        return workOrder;
    }

    public WorkOrderStatus getPreviousStatus() {
        return previousStatus;
    }

    public String getAction() {
        return action;
    }

    public Long getOperatorId() {
        return operatorId;
    }

    public Map<String, Object> getExtra() {
        return extra;
    }
}
