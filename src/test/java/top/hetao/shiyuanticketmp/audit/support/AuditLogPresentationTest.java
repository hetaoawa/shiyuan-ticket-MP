package top.hetao.shiyuanticketmp.audit.support;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class AuditLogPresentationTest {

    @Test
    void webhookSourceOnlyAppliesToCreateAction() {
        Map<String, Object> detail = Map.of("createdViaWebhook", true);

        assertThat(AuditLogPresentation.sourceCode("CREATE", detail, 12L))
                .isEqualTo(AuditLogPresentation.SOURCE_WEBHOOK);
        assertThat(AuditLogPresentation.sourceCode("ASSIGN", detail, 12L))
                .isEqualTo(AuditLogPresentation.SOURCE_WEB);
        assertThat(AuditLogPresentation.sourceCode(
                "ASSIGN", Map.of("autoAssignment", true), null))
                .isEqualTo(AuditLogPresentation.SOURCE_SYSTEM);
    }

    @Test
    void createsReadableActionAndStructuredSummary() {
        Map<String, Object> detail = Map.of(
                "previousStatus", "PENDING",
                "currentStatus", "IN_PROGRESS",
                "assigneeName", "云仓小李");

        assertThat(AuditLogPresentation.actionLabel("ASSIGN")).isEqualTo("派发工单");
        assertThat(AuditLogPresentation.detailSummary("ASSIGN", detail))
                .isEqualTo("派发给云仓小李，状态为处理中");
    }

    @Test
    void resubmitSummaryListsChangedFieldsWithoutRepeatingFieldValues() {
        Map<String, Object> detail = Map.of(
                "previousStatus", "REJECTED",
                "currentStatus", "PENDING",
                "changedFields", java.util.List.of(
                        "title", "description", "trackingNo", "targetAddress", "type", "priority"));

        assertThat(AuditLogPresentation.detailSummary("RESUBMIT", detail))
                .isEqualTo("重新提交工单，状态由已驳回变为待处理，修改：标题、描述、物流单号、目标地址、工单类型、优先级");
    }

    @Test
    void integrationUpdateSummaryShowsTypeAndSafeEnabledState() {
        assertThat(AuditLogPresentation.detailSummary(
                "UPDATE", Map.of("type", "AI", "enabled", true, "publicFields", "apiUrl")))
                .isEqualTo("更新 AI 集成配置，状态：已启用");
    }
}
