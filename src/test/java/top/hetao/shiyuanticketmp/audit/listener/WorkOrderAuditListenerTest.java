package top.hetao.shiyuanticketmp.audit.listener;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import top.hetao.shiyuanticketmp.audit.entity.SysAuditLog;
import top.hetao.shiyuanticketmp.audit.service.AuditLogService;
import top.hetao.shiyuanticketmp.auth.entity.SysUser;
import top.hetao.shiyuanticketmp.auth.mapper.SysUserMapper;
import top.hetao.shiyuanticketmp.workorder.entity.WorkOrder;
import top.hetao.shiyuanticketmp.workorder.enums.WorkOrderStatus;
import top.hetao.shiyuanticketmp.workorder.enums.WorkOrderType;
import top.hetao.shiyuanticketmp.workorder.event.WorkOrderStateChangedEvent;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class WorkOrderAuditListenerTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final AuditLogService auditLogService = mock(AuditLogService.class);
    private final SysUserMapper userMapper = mock(SysUserMapper.class);
    private final WorkOrderAuditListener listener =
            new WorkOrderAuditListener(auditLogService, objectMapper, userMapper);

    @Test
    void recordsReadableOperatorAndWebhookCreateSourceWithoutFakeZeroUser() throws Exception {
        SysUser operator = new SysUser();
        operator.setId(8L);
        operator.setNickname("货主张三");
        when(userMapper.selectByIdIgnoreTenant(8L)).thenReturn(operator);

        WorkOrder order = order(WorkOrderStatus.PENDING);
        order.setTrackingNo("YT100");
        order.setType(WorkOrderType.CHANGE_ADDRESS);
        order.setPriority(2);
        order.setTargetAddress("张三, 13800138000, 广东省深圳市南山区1号");
        order.setSubmitterId(8L);
        listener.onWorkOrderStateChanged(new WorkOrderStateChangedEvent(
                this, 9L, order, null, "CREATE", 8L, Map.of()));

        ArgumentCaptor<SysAuditLog> captor = ArgumentCaptor.forClass(SysAuditLog.class);
        verify(auditLogService).saveForTenant(captor.capture());
        SysAuditLog saved = captor.getValue();
        JsonNode detail = objectMapper.readTree(saved.getDetail());
        assertThat(saved.getOperatorId()).isEqualTo(8L);
        assertThat(detail.path("operatorName").asText()).isEqualTo("货主张三");
        assertThat(detail.path("requestSource").asText()).isEqualTo("WEBHOOK");
        assertThat(detail.path("actionLabel").asText()).isEqualTo("创建工单");
        assertThat(detail.path("trackingNo").asText()).isEqualTo("YT100");
        assertThat(detail.path("type").asText()).isEqualTo("CHANGE_ADDRESS");
        assertThat(detail.path("priority").asInt()).isEqualTo(2);
        assertThat(detail.path("targetAddress").asText())
                .isEqualTo("张三, 13800138000, 广东省深圳市南山区1号");
        assertThat(detail.path("submitterId").asLong()).isEqualTo(8L);
    }

    @Test
    void laterManualActionOnWebhookOrderIsStillWebSource() throws Exception {
        SysUser operator = new SysUser();
        operator.setId(9L);
        operator.setUsername("warehouse-admin");
        when(userMapper.selectByIdIgnoreTenant(9L)).thenReturn(operator);

        WorkOrder order = order(WorkOrderStatus.IN_PROGRESS);
        listener.onWorkOrderStateChanged(new WorkOrderStateChangedEvent(
                this, 9L, order, WorkOrderStatus.PENDING, "ASSIGN", 9L,
                Map.of("assigneeRoleCode", "WAREHOUSE_ADMIN")));

        ArgumentCaptor<SysAuditLog> captor = ArgumentCaptor.forClass(SysAuditLog.class);
        verify(auditLogService).saveForTenant(captor.capture());
        JsonNode detail = objectMapper.readTree(captor.getValue().getDetail());
        assertThat(detail.path("requestSource").asText()).isEqualTo("WEB");
    }

    @Test
    void systemActionKeepsNullableOperatorId() {
        WorkOrder order = order(WorkOrderStatus.IN_PROGRESS);
        listener.onWorkOrderStateChanged(new WorkOrderStateChangedEvent(
                this, 9L, order, WorkOrderStatus.PENDING, "ASSIGN", null,
                Map.of("autoAssignment", true, "assigneeRoleCode", "WAREHOUSE_ADMIN")));

        ArgumentCaptor<SysAuditLog> captor = ArgumentCaptor.forClass(SysAuditLog.class);
        verify(auditLogService).saveForTenant(captor.capture());
        assertThat(captor.getValue().getOperatorId()).isNull();
    }

    private WorkOrder order(WorkOrderStatus status) {
        WorkOrder order = new WorkOrder();
        order.setId(100L);
        order.setTenantId(9L);
        order.setTitle("测试工单");
        order.setStatus(status);
        order.setCreatedViaWebhook(true);
        return order;
    }
}
