package top.hetao.shiyuanticketmp.webhook.sender;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import top.hetao.shiyuanticketmp.tenant.service.TenantService;
import top.hetao.shiyuanticketmp.tenant.setting.service.TenantIntegrationSettingService;
import top.hetao.shiyuanticketmp.webhook.deadletter.WebhookDeadLetterService;
import top.hetao.shiyuanticketmp.workorder.comment.entity.WorkOrderComment;
import top.hetao.shiyuanticketmp.workorder.entity.WorkOrder;
import top.hetao.shiyuanticketmp.workorder.enums.WorkOrderStatus;
import top.hetao.shiyuanticketmp.workorder.event.WorkOrderCommentEvent;
import top.hetao.shiyuanticketmp.workorder.event.WorkOrderEvent;
import top.hetao.shiyuanticketmp.workorder.event.WorkOrderStateChangedEvent;
import top.hetao.shiyuanticketmp.workorder.listener.WorkOrderWebhookListener;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class WebhookTenantLinkTest {

    @Test
    void dispatcherBodiesPreserveFormatsAndExistingBaseQuery() throws Exception {
        ObjectMapper objectMapper = new ObjectMapper();
        WebhookDeadLetterService deadLetters = mock(WebhookDeadLetterService.class);
        DingTalkDispatcher dingTalk = new DingTalkDispatcher(objectMapper, deadLetters);
        CargoOwnerDispatcher cargoOwner = new CargoOwnerDispatcher(objectMapper, deadLetters);
        ReflectionTestUtils.setField(dingTalk, "workOrderDetailBaseUrl",
                "https://example.test/base?source=push");
        ReflectionTestUtils.setField(cargoOwner, "workOrderDetailBaseUrl",
                "https://example.test/base?source=push");
        WorkOrderEvent event = dispatcherEvent("tenant-100");

        String dingTalkBody = new String(dingTalk.prepareBatchBody(List.of(event)), UTF_8);
        String cargoOwnerBody = new String(cargoOwner.prepareBatchBody(List.of(event)), UTF_8);

        assertTrue(dingTalkBody.contains("- **处理链接**：[查看详情]("));
        assertTrue(dingTalkBody.contains(
                "https://example.test/base/workorder/detail/123?source=push&tenantCode=tenant-100"));
        assertTrue(cargoOwnerBody.contains("处理链接："));
        assertTrue(cargoOwnerBody.contains(
                "https://example.test/base/workorder/detail/123?source=push&tenantCode=tenant-100"));
    }

    @Test
    void dispatchersOmitDetailLinkWhenBaseUrlIsBlank() throws Exception {
        ObjectMapper objectMapper = new ObjectMapper();
        WebhookDeadLetterService deadLetters = mock(WebhookDeadLetterService.class);
        DingTalkDispatcher dingTalk = new DingTalkDispatcher(objectMapper, deadLetters);
        CargoOwnerDispatcher cargoOwner = new CargoOwnerDispatcher(objectMapper, deadLetters);
        ReflectionTestUtils.setField(dingTalk, "workOrderDetailBaseUrl", "  ");
        ReflectionTestUtils.setField(cargoOwner, "workOrderDetailBaseUrl", "  ");
        WorkOrderEvent event = dispatcherEvent("tenant-100");

        String dingTalkBody = new String(dingTalk.prepareBatchBody(List.of(event)), UTF_8);
        String cargoOwnerBody = new String(cargoOwner.prepareBatchBody(List.of(event)), UTF_8);

        assertFalse(dingTalkBody.contains("查看详情"));
        assertFalse(cargoOwnerBody.contains("处理链接"));
    }

    @Test
    void dispatchersRejectTenantAmbiguousEvents() {
        ObjectMapper objectMapper = new ObjectMapper();
        WebhookDeadLetterService deadLetters = mock(WebhookDeadLetterService.class);
        DingTalkDispatcher dingTalk = new DingTalkDispatcher(objectMapper, deadLetters);
        CargoOwnerDispatcher cargoOwner = new CargoOwnerDispatcher(objectMapper, deadLetters);
        ReflectionTestUtils.setField(dingTalk, "workOrderDetailBaseUrl", "https://example.test");
        ReflectionTestUtils.setField(cargoOwner, "workOrderDetailBaseUrl", "https://example.test");

        assertThrows(IllegalArgumentException.class,
                () -> dingTalk.prepareBatchBody(List.of(dispatcherEvent(null))));
        assertThrows(IllegalArgumentException.class,
                () -> cargoOwner.prepareBatchBody(List.of(dispatcherEvent("  "))));
    }

    @Test
    void dingTalkAllowsBlankOptionalDetailBaseUrl() {
        DingTalkDispatcher dingTalk = new DingTalkDispatcher(
                new ObjectMapper(), mock(WebhookDeadLetterService.class));
        ReflectionTestUtils.setField(dingTalk, "accessToken", "robot-token");
        ReflectionTestUtils.setField(dingTalk, "secret", "robot-secret");
        ReflectionTestUtils.setField(dingTalk, "workOrderDetailBaseUrl", "  ");

        assertDoesNotThrow(dingTalk::buildRequestUrl);
    }

    @Test
    void stateChangeQueuesPayloadWithResolvedTenantCode() {
        WebhookMessageAggregator aggregator = mock(WebhookMessageAggregator.class);
        TenantIntegrationSettingService settings = mock(TenantIntegrationSettingService.class);
        TenantService tenantService = mock(TenantService.class);
        when(settings.dingTalkPushEnabled(100L)).thenReturn(true);
        when(tenantService.getTenantCode(100L)).thenReturn("tenant-100");
        WorkOrderWebhookListener listener = new WorkOrderWebhookListener(
                aggregator, settings, tenantService);

        listener.onWorkOrderStateChanged(new WorkOrderStateChangedEvent(
                this, 100L, workOrder(100L, null), WorkOrderStatus.PENDING,
                "ASSIGN", 7L, Map.of()));

        org.mockito.ArgumentCaptor<WorkOrderEvent> payload =
                org.mockito.ArgumentCaptor.forClass(WorkOrderEvent.class);
        verify(aggregator).submit(payload.capture());
        assertEquals("tenant-100", payload.getValue().getTenantCode());
    }

    @Test
    void stateChangeRejectsMissingTenantCodeBeforeQueueing() {
        WebhookMessageAggregator aggregator = mock(WebhookMessageAggregator.class);
        TenantIntegrationSettingService settings = mock(TenantIntegrationSettingService.class);
        TenantService tenantService = mock(TenantService.class);
        when(settings.dingTalkPushEnabled(100L)).thenReturn(true);
        when(tenantService.getTenantCode(100L)).thenReturn(null);
        WorkOrderWebhookListener listener = new WorkOrderWebhookListener(
                aggregator, settings, tenantService);

        assertThrows(IllegalStateException.class, () -> listener.onWorkOrderStateChanged(
                new WorkOrderStateChangedEvent(this, 100L, workOrder(100L, null),
                        WorkOrderStatus.PENDING, "ASSIGN", 7L, Map.of())));

        verify(aggregator, never()).submit(any());
    }

    @Test
    void commentQueuesPayloadWithResolvedTenantCode() {
        WebhookMessageAggregator aggregator = mock(WebhookMessageAggregator.class);
        TenantIntegrationSettingService settings = mock(TenantIntegrationSettingService.class);
        TenantService tenantService = mock(TenantService.class);
        when(tenantService.getTenantCode(100L)).thenReturn("tenant-100");
        WorkOrderWebhookListener listener = new WorkOrderWebhookListener(
                aggregator, settings, tenantService);

        listener.onWorkOrderComment(new WorkOrderCommentEvent(
                this, 100L, workOrder(100L, "room-1"), comment()));

        org.mockito.ArgumentCaptor<WorkOrderEvent> payload =
                org.mockito.ArgumentCaptor.forClass(WorkOrderEvent.class);
        verify(aggregator).submit(payload.capture());
        assertEquals("tenant-100", payload.getValue().getTenantCode());
    }

    @Test
    void commentRejectsBlankTenantCodeBeforeQueueing() {
        WebhookMessageAggregator aggregator = mock(WebhookMessageAggregator.class);
        TenantIntegrationSettingService settings = mock(TenantIntegrationSettingService.class);
        TenantService tenantService = mock(TenantService.class);
        when(tenantService.getTenantCode(100L)).thenReturn("  ");
        WorkOrderWebhookListener listener = new WorkOrderWebhookListener(
                aggregator, settings, tenantService);

        assertThrows(IllegalStateException.class, () -> listener.onWorkOrderComment(
                new WorkOrderCommentEvent(this, 100L, workOrder(100L, "room-1"), comment())));

        verify(aggregator, never()).submit(any());
    }

    private static WorkOrder workOrder(Long tenantId, String conversationId) {
        WorkOrder order = new WorkOrder();
        order.setId(123L);
        order.setTenantId(tenantId);
        order.setTitle("测试工单");
        order.setStatus(WorkOrderStatus.IN_PROGRESS);
        order.setCreatedAt(LocalDateTime.of(2026, 7, 14, 10, 0));
        order.setConversationId(conversationId);
        order.setSenderStaffId("staff-1");
        return order;
    }

    private static WorkOrderComment comment() {
        WorkOrderComment comment = new WorkOrderComment();
        comment.setContent("请尽快处理");
        comment.setCommenterId(9L);
        return comment;
    }

    private static WorkOrderEvent dispatcherEvent(String tenantCode) {
        WorkOrderEvent event = new WorkOrderEvent();
        event.setWorkOrderId(123L);
        event.setTenantId(100L);
        event.setTenantCode(tenantCode);
        event.setStatus("ASSIGNED");
        event.setCreatedAt(LocalDateTime.of(2026, 7, 14, 10, 0));
        return event;
    }
}
