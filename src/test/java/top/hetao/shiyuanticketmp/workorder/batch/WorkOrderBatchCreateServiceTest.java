package top.hetao.shiyuanticketmp.workorder.batch;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import top.hetao.shiyuanticketmp.common.context.TenantContext;
import top.hetao.shiyuanticketmp.workorder.batch.entity.WorkOrderBatchRequest;
import top.hetao.shiyuanticketmp.workorder.batch.mapper.WorkOrderBatchRequestMapper;
import top.hetao.shiyuanticketmp.workorder.controller.dto.BatchCreateWorkOrderItem;
import top.hetao.shiyuanticketmp.workorder.controller.dto.BatchCreateWorkOrderRequest;
import top.hetao.shiyuanticketmp.workorder.entity.WorkOrder;
import top.hetao.shiyuanticketmp.workorder.exception.WorkOrderException;
import top.hetao.shiyuanticketmp.workorder.service.WorkOrderService;

import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class WorkOrderBatchCreateServiceTest {
    private final WorkOrderBatchRequestMapper mapper = mock(WorkOrderBatchRequestMapper.class);
    private final WorkOrderService workOrderService = mock(WorkOrderService.class);
    private final WorkOrderBatchCreateService service = new WorkOrderBatchCreateService(
            mapper, workOrderService, new ObjectMapper());

    @Test
    void createsInOrderAndPersistsOrderedIds() {
        AtomicReference<String> hash = new AtomicReference<>();
        when(mapper.insertClaim(anyLong(), eq(7L), eq(9L), eq("request_123"), any()))
                .thenAnswer(invocation -> {
                    hash.set(invocation.getArgument(4));
                    return 1;
                });
        when(mapper.selectClaimForUpdate(7L, 9L, "request_123"))
                .thenAnswer(invocation -> claim(hash.get(), null));
        when(mapper.completeClaim(anyLong(), any())).thenReturn(1);
        AtomicLong ids = new AtomicLong(100);
        when(workOrderService.create(any())).thenAnswer(invocation -> {
            WorkOrder order = invocation.getArgument(0);
            order.setId(ids.incrementAndGet());
            return order;
        });

        try (TenantContext.Scope ignored = TenantContext.useTenant(7L)) {
            BatchCreateResult result = service.create(request("YT1", "YT2"), "request_123", 9L);
            assertThat(result.workOrderIds()).containsExactly(101L, 102L);
            assertThat(result.replayed()).isFalse();
        }

        ArgumentCaptor<String> json = ArgumentCaptor.forClass(String.class);
        verify(mapper).completeClaim(anyLong(), json.capture());
        assertThat(json.getValue()).isEqualTo("[\"101\",\"102\"]");
    }

    @Test
    void sameKeyAndHashReplaysWithoutCreatingEventsOrOrders() {
        BatchCreateWorkOrderRequest request = request("YT1");
        AtomicReference<String> hash = new AtomicReference<>();
        when(mapper.insertClaim(anyLong(), eq(7L), eq(9L), eq("request_123"), any()))
                .thenAnswer(invocation -> {
                    hash.set(invocation.getArgument(4));
                    return 0;
                });
        when(mapper.selectClaimForUpdate(7L, 9L, "request_123"))
                .thenAnswer(invocation -> claim(hash.get(), "[\"55\"]"));

        try (TenantContext.Scope ignored = TenantContext.useTenant(7L)) {
            BatchCreateResult result = service.create(request, "request_123", 9L);
            assertThat(result.workOrderIds()).containsExactly(55L);
            assertThat(result.replayed()).isTrue();
        }
        verify(workOrderService, never()).create(any());
    }

    @Test
    void rejectsDuplicateTrackingNumbersBeforeClaim() {
        assertThatThrownBy(() -> {
            try (TenantContext.Scope ignored = TenantContext.useTenant(7L)) {
                service.create(request("YT1", "yt1"), "request_123", 9L);
            }
        }).isInstanceOf(WorkOrderException.class).hasMessageContaining("运单号重复");
        verify(mapper, never()).insertClaim(anyLong(), anyLong(), anyLong(), any(), any());
    }

    private WorkOrderBatchRequest claim(String hash, String ids) {
        WorkOrderBatchRequest result = new WorkOrderBatchRequest();
        result.setId(1L);
        result.setRequestHash(hash);
        result.setWorkOrderIdsJson(ids);
        return result;
    }

    private BatchCreateWorkOrderRequest request(String... trackingNumbers) {
        BatchCreateWorkOrderRequest request = new BatchCreateWorkOrderRequest();
        request.setItems(java.util.Arrays.stream(trackingNumbers).map(tracking -> {
            BatchCreateWorkOrderItem item = new BatchCreateWorkOrderItem();
            item.setTitle("title " + tracking);
            item.setTrackingNo(tracking);
            item.setType("INTERCEPT");
            item.setPriority(1);
            return item;
        }).toList());
        return request;
    }
}
