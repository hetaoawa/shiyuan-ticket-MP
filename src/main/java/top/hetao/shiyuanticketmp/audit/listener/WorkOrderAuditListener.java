package top.hetao.shiyuanticketmp.audit.listener;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import top.hetao.shiyuanticketmp.audit.entity.SysAuditLog;
import top.hetao.shiyuanticketmp.audit.service.AuditLogService;
import top.hetao.shiyuanticketmp.audit.support.AuditLogPresentation;
import top.hetao.shiyuanticketmp.auth.entity.SysUser;
import top.hetao.shiyuanticketmp.auth.mapper.SysUserMapper;
import top.hetao.shiyuanticketmp.workorder.entity.WorkOrder;
import top.hetao.shiyuanticketmp.workorder.event.WorkOrderStateChangedEvent;

import java.util.LinkedHashMap;
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
    private final SysUserMapper userMapper;

    public WorkOrderAuditListener(AuditLogService auditLogService,
                                  ObjectMapper objectMapper,
                                  SysUserMapper userMapper) {
        this.auditLogService = auditLogService;
        this.objectMapper = objectMapper;
        this.userMapper = userMapper;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    @Async("webhookExecutor")
    public void onWorkOrderStateChanged(WorkOrderStateChangedEvent event) {
        WorkOrder order = event.getWorkOrder();

        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("previousStatus", event.getPreviousStatus() != null ? event.getPreviousStatus().name() : null);
        detail.put("currentStatus", order.getStatus().name());
        detail.put("title", order.getTitle());
        if (event.getExtra() != null) {
            detail.putAll(event.getExtra());
        }
        if ("CREATE".equals(event.getAction())) {
            detail.put("trackingNo", order.getTrackingNo());
            detail.put("type", order.getType() == null ? null : order.getType().name());
            detail.put("priority", order.getPriority());
            detail.put("targetAddress", order.getTargetAddress());
            detail.put("submitterId", order.getSubmitterId());
        }
        if ("CREATE".equals(event.getAction()) && Boolean.TRUE.equals(order.getCreatedViaWebhook())) {
            detail.put("createdViaWebhook", true);
        }

        String operatorName = resolveUserName(event.getOperatorId(), "系统");
        detail.put("actionLabel", AuditLogPresentation.actionLabel(event.getAction()));
        detail.put("operatorName", operatorName);
        String requestSource = AuditLogPresentation.sourceCode(
                event.getAction(), detail, event.getOperatorId());
        detail.put("requestSource", requestSource);
        detail.put("requestSourceLabel", AuditLogPresentation.sourceLabel(requestSource));
        Object assigneeId = detail.get("assigneeId");
        if (assigneeId instanceof Number number) {
            detail.put("assigneeName", resolveUserName(number.longValue(), ""));
        }

        SysAuditLog auditLog = new SysAuditLog();
        auditLog.setBizType("WORK_ORDER");
        auditLog.setBizId(order.getId());
        auditLog.setAction(event.getAction());
        // 数据库允许系统操作的 operatorId 为空，避免把 0 伪装成真实用户。
        auditLog.setOperatorId(event.getOperatorId());
        auditLog.setTenantId(event.getTenantId());

        try {
            auditLog.setDetail(objectMapper.writeValueAsString(detail));
        } catch (Exception e) {
            auditLog.setDetail(detail.toString());
        }

        try {
            auditLogService.saveForTenant(auditLog);
            log.info("[审计] 记录成功 bizType=WORK_ORDER bizId={} action={}", order.getId(), event.getAction());
        } catch (Exception e) {
            log.error("[审计] 记录失败 bizId={} action={}", order.getId(), event.getAction(), e);
        }
    }

    private String resolveUserName(Long userId, String fallback) {
        if (userId == null || userId == 0L) {
            return fallback;
        }
        try {
            SysUser user = userMapper.selectByIdIgnoreTenant(userId);
            if (user != null) {
                if (user.getNickname() != null && !user.getNickname().isBlank()) {
                    return user.getNickname();
                }
                if (user.getUsername() != null && !user.getUsername().isBlank()) {
                    return user.getUsername();
                }
            }
        } catch (RuntimeException e) {
            log.warn("[审计] 无法读取用户展示名 userId={}", userId, e);
        }
        return "用户（ID: " + userId + "）";
    }
}
