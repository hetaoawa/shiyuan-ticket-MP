package top.hetao.shiyuanticketmp.audit.listener;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import top.hetao.shiyuanticketmp.audit.entity.SysAuditLog;
import top.hetao.shiyuanticketmp.audit.service.AuditLogService;
import top.hetao.shiyuanticketmp.workorder.entity.WorkOrder;
import top.hetao.shiyuanticketmp.workorder.event.WorkOrderStateChangedEvent;

import java.util.HashMap;
import java.util.Map;

/**
 * 工单状态变更事件监听器 — 审计日志记录。
 *
 * <p>异步记录每次工单状态变更，不影响主流程性能。
 */
@Component
public class WorkOrderAuditListener {

    private static final Logger log = LoggerFactory.getLogger(WorkOrderAuditListener.class);

    private final AuditLogService auditLogService;
    private final ObjectMapper objectMapper;

    public WorkOrderAuditListener(AuditLogService auditLogService, ObjectMapper objectMapper) {
        this.auditLogService = auditLogService;
        this.objectMapper = objectMapper;
    }

    @EventListener
    @Async("webhookExecutor")
    public void onWorkOrderStateChanged(WorkOrderStateChangedEvent event) {
        WorkOrder order = event.getWorkOrder();

        Map<String, Object> detail = new HashMap<>();
        detail.put("previousStatus", event.getPreviousStatus() != null ? event.getPreviousStatus().name() : null);
        detail.put("currentStatus", order.getStatus().name());
        detail.put("title", order.getTitle());
        if (event.getExtra() != null) {
            detail.putAll(event.getExtra());
        }

        SysAuditLog auditLog = new SysAuditLog();
        auditLog.setBizType("WORK_ORDER");
        auditLog.setBizId(order.getId());
        auditLog.setAction(event.getAction());
        // operatorId 可能为 null（如系统自动操作），设置默认值 0
        auditLog.setOperatorId(event.getOperatorId() != null ? event.getOperatorId() : 0L);

        try {
            auditLog.setDetail(objectMapper.writeValueAsString(detail));
        } catch (Exception e) {
            auditLog.setDetail(detail.toString());
        }

        try {
            auditLogService.save(auditLog);
            log.info("[审计] 记录成功 bizType=WORK_ORDER bizId={} action={}", order.getId(), event.getAction());
        } catch (Exception e) {
            log.error("[审计] 记录失败 bizId={} action={}", order.getId(), event.getAction(), e);
        }
    }
}
