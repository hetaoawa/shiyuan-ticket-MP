package top.hetao.shiyuanticketmp.workorder.service.impl;

import cn.dev33.satoken.SaManager;
import cn.dev33.satoken.context.SaTokenContext;
import cn.dev33.satoken.context.SaTokenContextForThreadLocal;
import cn.dev33.satoken.context.SaTokenContextForThreadLocalStorage;
import cn.dev33.satoken.context.model.SaRequest;
import cn.dev33.satoken.context.model.SaResponse;
import cn.dev33.satoken.context.model.SaStorage;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.ApplicationEventPublisher;
import top.hetao.shiyuanticketmp.auth.mapper.SysRoleMapper;
import top.hetao.shiyuanticketmp.auth.mapper.SysUserMapper;
import top.hetao.shiyuanticketmp.common.context.TenantContext;
import top.hetao.shiyuanticketmp.tenant.service.TenantLifecycleGuard;
import top.hetao.shiyuanticketmp.workorder.cache.WorkOrderCacheManager;
import top.hetao.shiyuanticketmp.workorder.entity.WorkOrder;
import top.hetao.shiyuanticketmp.workorder.enums.WorkOrderStatus;
import top.hetao.shiyuanticketmp.workorder.enums.WorkOrderType;
import top.hetao.shiyuanticketmp.workorder.event.WorkOrderStateChangedEvent;
import top.hetao.shiyuanticketmp.workorder.mapper.WorkOrderMapper;
import top.hetao.shiyuanticketmp.workorder.service.WorkOrderTypeResolver;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class WorkOrderServiceAuditTest {

    @Test
    void resubmitEventRecordsOnlyChangedFieldNames() {
        WorkOrderMapper mapper = mock(WorkOrderMapper.class);
        ApplicationEventPublisher publisher = mock(ApplicationEventPublisher.class);
        WorkOrderServiceImpl service = new WorkOrderServiceImpl(
                mapper, publisher, mock(WorkOrderCacheManager.class),
                mock(WorkOrderTypeResolver.class), mock(SysRoleMapper.class),
                mock(SysUserMapper.class), mock(TenantLifecycleGuard.class));

        WorkOrder existing = new WorkOrder();
        existing.setId(100L);
        existing.setTenantId(9L);
        existing.setStatus(WorkOrderStatus.REJECTED);
        existing.setTitle("旧标题");
        existing.setDescription("旧描述");
        existing.setTrackingNo("YT100");
        existing.setTargetAddress("旧地址");
        existing.setPriority(2);
        existing.setType(WorkOrderType.OTHER);
        when(mapper.selectById(100L)).thenReturn(existing);
        when(mapper.resubmitRejected(eq(100L), any(), any(), any(), any(),
                any(), any(), any(LocalDateTime.class))).thenReturn(1);

        WorkOrder update = new WorkOrder();
        update.setTitle("新标题");
        update.setDescription("新描述");
        update.setTrackingNo("YT100");
        update.setTargetAddress("新地址");
        update.setPriority(2);
        update.setType(WorkOrderType.CHANGE_ADDRESS);
        SaTokenContext originalContext = SaManager.getSaTokenContext();
        try {
            SaManager.setSaTokenContext(new SaTokenContextForThreadLocal());
            SaTokenContextForThreadLocalStorage.setBox(
                    mock(SaRequest.class), mock(SaResponse.class), mock(SaStorage.class));
            try (TenantContext.Scope ignored = TenantContext.useTenant(9L)) {
                service.resubmit(100L, update);
            }
        } finally {
            SaTokenContextForThreadLocalStorage.clearBox();
            SaManager.setSaTokenContext(originalContext);
        }

        ArgumentCaptor<WorkOrderStateChangedEvent> eventCaptor =
                ArgumentCaptor.forClass(WorkOrderStateChangedEvent.class);
        verify(publisher).publishEvent(eventCaptor.capture());
        WorkOrderStateChangedEvent event = eventCaptor.getValue();
        assertThat(event.getExtra().get("changedFields"))
                .isEqualTo(List.of("title", "description", "targetAddress", "type"));
    }
}
