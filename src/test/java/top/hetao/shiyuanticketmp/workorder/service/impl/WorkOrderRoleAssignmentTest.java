package top.hetao.shiyuanticketmp.workorder.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;
import top.hetao.shiyuanticketmp.auth.entity.SysRole;
import top.hetao.shiyuanticketmp.auth.mapper.SysRoleMapper;
import top.hetao.shiyuanticketmp.auth.mapper.SysUserMapper;
import top.hetao.shiyuanticketmp.common.context.TenantContext;
import top.hetao.shiyuanticketmp.tenant.service.TenantLifecycleGuard;
import top.hetao.shiyuanticketmp.workorder.cache.WorkOrderCacheManager;
import top.hetao.shiyuanticketmp.workorder.entity.WorkOrder;
import top.hetao.shiyuanticketmp.workorder.enums.WorkOrderStatus;
import top.hetao.shiyuanticketmp.workorder.exception.WorkOrderException;
import top.hetao.shiyuanticketmp.workorder.mapper.WorkOrderMapper;
import top.hetao.shiyuanticketmp.workorder.service.WorkOrderTypeResolver;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class WorkOrderRoleAssignmentTest {

    @Test
    void assignByRoleRejectsWhenCurrentTenantHasNoActiveWarehouseAdmin() {
        WorkOrderMapper mapper = mock(WorkOrderMapper.class);
        SysRoleMapper roleMapper = mock(SysRoleMapper.class);
        SysUserMapper userMapper = mock(SysUserMapper.class);
        TenantLifecycleGuard tenantLifecycleGuard = mock(TenantLifecycleGuard.class);
        WorkOrderServiceImpl service = new WorkOrderServiceImpl(
                mapper, mock(ApplicationEventPublisher.class), mock(WorkOrderCacheManager.class),
                mock(WorkOrderTypeResolver.class), roleMapper, userMapper, tenantLifecycleGuard);

        WorkOrder order = new WorkOrder();
        order.setId(100L);
        order.setTenantId(9L);
        order.setStatus(WorkOrderStatus.PENDING);
        when(mapper.selectById(100L)).thenReturn(order);

        SysRole warehouseAdmin = new SysRole();
        warehouseAdmin.setId(20L);
        warehouseAdmin.setTenantId(9L);
        warehouseAdmin.setRoleCode("WAREHOUSE_ADMIN");
        when(roleMapper.selectList(anyRoleQuery())).thenReturn(List.of(warehouseAdmin));
        when(userMapper.existsActiveUserByTenantAndRoleCode(9L, "WAREHOUSE_ADMIN"))
                .thenReturn(false);

        try (TenantContext.Scope ignored = TenantContext.useTenant(9L)) {
            assertThatThrownBy(() -> service.assignByRole(100L, "WAREHOUSE_ADMIN"))
                    .isInstanceOf(WorkOrderException.class)
                    .hasMessageContaining("请先创建并启用用户")
                    .hasMessageContaining("再派发工单");
        }

        verify(tenantLifecycleGuard).lockWritableTenant(9L);
        verify(userMapper).existsActiveUserByTenantAndRoleCode(9L, "WAREHOUSE_ADMIN");
        verify(mapper, never()).assignPendingToRole(any(), any(), any());
    }

    @Test
    void batchAssignByRolePropagatesMissingActiveUserBeforeProcessingOrders() {
        WorkOrderMapper mapper = mock(WorkOrderMapper.class);
        SysRoleMapper roleMapper = mock(SysRoleMapper.class);
        SysUserMapper userMapper = mock(SysUserMapper.class);
        WorkOrderServiceImpl service = new WorkOrderServiceImpl(
                mapper, mock(ApplicationEventPublisher.class), mock(WorkOrderCacheManager.class),
                mock(WorkOrderTypeResolver.class), roleMapper, userMapper,
                mock(TenantLifecycleGuard.class));

        SysRole warehouseAdmin = new SysRole();
        warehouseAdmin.setTenantId(9L);
        warehouseAdmin.setRoleCode("WAREHOUSE_ADMIN");
        when(roleMapper.selectList(anyRoleQuery())).thenReturn(List.of(warehouseAdmin));
        when(userMapper.existsActiveUserByTenantAndRoleCode(9L, "WAREHOUSE_ADMIN"))
                .thenReturn(false);

        try (TenantContext.Scope ignored = TenantContext.useTenant(9L)) {
            assertThatThrownBy(() -> service.batchAssignByRole(List.of(100L, 101L), "WAREHOUSE_ADMIN"))
                    .isInstanceOf(WorkOrderException.class)
                    .hasMessageContaining("请先创建并启用用户");
        }

        verify(mapper, never()).selectById(any());
        verify(mapper, never()).assignPendingToRole(any(), any(), any());
    }

    @Test
    void autoAssignmentSkipsOrderWhenWarehouseRoleHasNoActiveUser() {
        WorkOrderMapper mapper = mock(WorkOrderMapper.class);
        SysRoleMapper roleMapper = mock(SysRoleMapper.class);
        SysUserMapper userMapper = mock(SysUserMapper.class);
        WorkOrderServiceImpl service = new WorkOrderServiceImpl(
                mapper, mock(ApplicationEventPublisher.class), mock(WorkOrderCacheManager.class),
                mock(WorkOrderTypeResolver.class), roleMapper, userMapper,
                mock(TenantLifecycleGuard.class));

        SysRole warehouseAdmin = new SysRole();
        warehouseAdmin.setTenantId(9L);
        warehouseAdmin.setRoleCode("WAREHOUSE_ADMIN");
        when(roleMapper.selectList(anyRoleQuery())).thenReturn(List.of(warehouseAdmin));
        when(userMapper.existsActiveUserByTenantAndRoleCode(9L, "WAREHOUSE_ADMIN"))
                .thenReturn(false);

        boolean assigned;
        try (TenantContext.Scope ignored = TenantContext.useTenant(9L)) {
            assigned = service.autoAssignExternalInbound(100L, 9L);
        }

        assertThat(assigned).isFalse();
        verify(mapper, never()).claimExternalInboundForWarehouseRole(any(), any(), any());
    }

    private static LambdaQueryWrapper<SysRole> anyRoleQuery() {
        return org.mockito.ArgumentMatchers.any();
    }
}
