package top.hetao.shiyuanticketmp.auth.service;

import cn.dev33.satoken.annotation.SaCheckPermission;
import cn.dev33.satoken.annotation.SaCheckRole;
import org.junit.jupiter.api.Test;
import top.hetao.shiyuanticketmp.auth.entity.SysPermission;
import top.hetao.shiyuanticketmp.auth.entity.SysRole;
import top.hetao.shiyuanticketmp.auth.entity.SysRolePermission;
import top.hetao.shiyuanticketmp.auth.mapper.SysPermissionMapper;
import top.hetao.shiyuanticketmp.auth.mapper.SysRolePermissionMapper;
import top.hetao.shiyuanticketmp.common.context.TenantContext;
import top.hetao.shiyuanticketmp.platform.ssl.controller.PlatformSslAdminController;
import top.hetao.shiyuanticketmp.tenant.service.TenantLifecycleGuard;
import top.hetao.shiyuanticketmp.workorder.exception.WorkOrderException;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RoleServicePlatformPermissionTest {

    private final SysRolePermissionMapper rolePermissionMapper = mock(SysRolePermissionMapper.class);
    private final SysPermissionMapper permissionMapper = mock(SysPermissionMapper.class);
    private final TenantLifecycleGuard lifecycleGuard = mock(TenantLifecycleGuard.class);

    @Test
    void tenantRoleCannotBindPlatformPermissionAndExistingBindingsAreNotDeleted() {
        RoleService service = spy(new RoleService(rolePermissionMapper, permissionMapper, lifecycleGuard));
        SysRole role = new SysRole();
        role.setId(41L);
        role.setTenantId(7L);
        role.setRoleCode("WAREHOUSE_ADMIN");
        doReturn(role).when(service).getById(41L);
        SysPermission platformPermission = permission(26001L, "platform:ssl:manage");
        when(permissionMapper.selectById(26001L)).thenReturn(platformPermission);

        try (TenantContext.Scope ignored = TenantContext.useTenant(7L)) {
            assertThatThrownBy(() -> service.assignPermissions(41L, List.of(26001L)))
                    .isInstanceOf(WorkOrderException.class)
                    .hasMessageContaining("租户角色不能绑定平台权限");
        }

        verify(rolePermissionMapper, never()).delete(any());
        verify(rolePermissionMapper, never()).insert(any(SysRolePermission.class));
    }

    @Test
    void tenantPermissionListOmitsPlatformPermissions() {
        RoleService service = new RoleService(rolePermissionMapper, permissionMapper, lifecycleGuard);
        when(permissionMapper.selectList(any())).thenReturn(List.of(
                permission(26001L, "platform:ssl:manage"),
                permission(1L, "workorder:view")));

        List<SysPermission> result;
        try (TenantContext.Scope ignored = TenantContext.useTenant(7L)) {
            result = service.listAllPermissions();
        }

        assertThat(result).extracting(SysPermission::getPermissionCode)
                .containsExactly("workorder:view");
    }

    @Test
    void platformSslControllerRequiresGlobalRoleAndPermission() {
        SaCheckRole role = PlatformSslAdminController.class.getAnnotation(SaCheckRole.class);
        SaCheckPermission permission = PlatformSslAdminController.class.getAnnotation(SaCheckPermission.class);

        assertThat(role).isNotNull();
        assertThat(role.value()).containsExactly("GLOBAL_SYSTEM_ADMIN");
        assertThat(permission).isNotNull();
        assertThat(permission.value()).containsExactly("platform:ssl:manage");
    }

    private SysPermission permission(long id, String code) {
        SysPermission permission = new SysPermission();
        permission.setId(id);
        permission.setPermissionCode(code);
        permission.setPermissionName(code);
        return permission;
    }
}
