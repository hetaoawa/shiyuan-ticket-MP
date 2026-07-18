package top.hetao.shiyuanticketmp.audit.support;

import java.util.Map;
import java.util.Collection;
import java.util.stream.Collectors;

/**
 * 审计日志的稳定展示语义，供写入快照和历史记录查询共同使用。
 */
public final class AuditLogPresentation {

    public static final String SOURCE_WEB = "WEB";
    public static final String SOURCE_WEBHOOK = "WEBHOOK";
    public static final String SOURCE_SYSTEM = "SYSTEM";
    private static final int MAX_SUMMARY_LENGTH = 240;

    private AuditLogPresentation() {
    }

    public static String actionLabel(String action) {
        if (action == null || action.isBlank()) {
            return "未知操作";
        }
        return switch (action) {
            case "CREATE" -> "创建工单";
            case "ASSIGN" -> "派发工单";
            case "CLOSE" -> "关闭工单";
            case "REJECT" -> "驳回工单";
            case "RESUBMIT" -> "重新提交";
            case "FORCE_REJECT" -> "强制驳回";
            case "UPDATE" -> "更新配置";
            default -> action;
        };
    }

    public static String sourceCode(String action, Map<String, Object> detail, Long operatorId) {
        String snapshot = text(detail, "requestSource");
        if (!snapshot.isBlank()) {
            return snapshot;
        }
        if (Boolean.TRUE.equals(detail == null ? null : detail.get("autoAssignment"))) {
            return SOURCE_SYSTEM;
        }
        if ("CREATE".equals(action)
                && Boolean.TRUE.equals(detail == null ? null : detail.get("createdViaWebhook"))) {
            return SOURCE_WEBHOOK;
        }
        return operatorId == null || operatorId == 0L ? SOURCE_SYSTEM : SOURCE_WEB;
    }

    public static String sourceLabel(String source) {
        return switch (source == null ? "" : source) {
            case SOURCE_WEB -> "管理后台";
            case SOURCE_WEBHOOK -> "外部 Webhook";
            case SOURCE_SYSTEM -> "系统自动";
            default -> source == null || source.isBlank() ? "未知来源" : source;
        };
    }

    public static String detailSummary(String action, Map<String, Object> detail) {
        String snapshot = text(detail, "detailSummary");
        if (!snapshot.isBlank()) {
            return truncate(snapshot);
        }

        String previousStatus = statusLabel(text(detail, "previousStatus"));
        String currentStatus = statusLabel(text(detail, "currentStatus"));
        String summary = switch (action == null ? "" : action) {
            case "CREATE" -> "创建工单，状态为" + defaultText(currentStatus, "待处理");
            case "ASSIGN" -> assignmentSummary(detail, currentStatus);
            case "CLOSE" -> appendValue("关闭工单", "处理结论", text(detail, "resolution"));
            case "REJECT" -> appendValue("驳回工单", "原因", text(detail, "reason"));
            case "RESUBMIT" -> appendChangedFields(
                    transitionSummary("重新提交工单", previousStatus, currentStatus), detail);
            case "FORCE_REJECT" -> appendValue(
                    transitionSummary("强制驳回工单", previousStatus, currentStatus),
                    "原因", text(detail, "reason"));
            case "UPDATE" -> integrationUpdateSummary(detail);
            default -> transitionSummary(actionLabel(action), previousStatus, currentStatus);
        };
        return truncate(summary);
    }

    public static String statusLabel(String status) {
        return switch (status == null ? "" : status) {
            case "PENDING" -> "待处理";
            case "IN_PROGRESS" -> "处理中";
            case "CLOSED" -> "已关闭";
            case "REJECTED" -> "已驳回";
            default -> status == null ? "" : status;
        };
    }

    public static String text(Map<String, Object> detail, String key) {
        if (detail == null) {
            return "";
        }
        Object value = detail.get(key);
        return value == null ? "" : String.valueOf(value).trim();
    }

    private static String assignmentSummary(Map<String, Object> detail, String currentStatus) {
        String assigneeName = text(detail, "assigneeName");
        String assigneeRole = text(detail, "assigneeRoleCode");
        String assigneeId = text(detail, "assigneeId");
        String target = !assigneeName.isBlank() ? assigneeName
                : !assigneeRole.isBlank() ? "角色 " + assigneeRole
                : !assigneeId.isBlank() ? "用户 " + assigneeId : "处理人";
        String prefix = Boolean.TRUE.equals(detail == null ? null : detail.get("autoAssignment"))
                ? "系统自动派发给" : "派发给";
        return prefix + target + (currentStatus.isBlank() ? "" : "，状态为" + currentStatus);
    }

    private static String transitionSummary(String prefix, String previousStatus, String currentStatus) {
        if (!previousStatus.isBlank() && !currentStatus.isBlank()) {
            return prefix + "，状态由" + previousStatus + "变为" + currentStatus;
        }
        if (!currentStatus.isBlank()) {
            return prefix + "，状态为" + currentStatus;
        }
        return prefix;
    }

    private static String appendValue(String prefix, String label, String value) {
        return value.isBlank() ? prefix : prefix + "，" + label + "：" + value;
    }

    private static String appendChangedFields(String prefix, Map<String, Object> detail) {
        Object rawFields = detail == null ? null : detail.get("changedFields");
        if (!(rawFields instanceof Collection<?> fields) || fields.isEmpty()) {
            return prefix;
        }
        String labels = fields.stream()
                .map(String::valueOf)
                .map(AuditLogPresentation::fieldLabel)
                .distinct()
                .collect(Collectors.joining("、"));
        return labels.isBlank() ? prefix : prefix + "，修改：" + labels;
    }

    private static String integrationUpdateSummary(Map<String, Object> detail) {
        String type = text(detail, "type");
        Object enabledValue = detail == null ? null : detail.get("enabled");
        String prefix = type.isBlank() ? "更新集成配置" : "更新 " + type + " 集成配置";
        if (enabledValue instanceof Boolean enabled) {
            return prefix + "，状态：" + (enabled ? "已启用" : "已停用");
        }
        return prefix;
    }

    private static String fieldLabel(String field) {
        return switch (field) {
            case "title" -> "标题";
            case "description" -> "描述";
            case "trackingNo" -> "物流单号";
            case "targetAddress" -> "目标地址";
            case "type" -> "工单类型";
            case "priority" -> "优先级";
            default -> field;
        };
    }

    private static String defaultText(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    private static String truncate(String value) {
        if (value != null) {
            value = value.replaceAll("\\s+", " ").trim();
        }
        if (value == null || value.length() <= MAX_SUMMARY_LENGTH) {
            return value;
        }
        return value.substring(0, MAX_SUMMARY_LENGTH - 1) + "…";
    }
}
