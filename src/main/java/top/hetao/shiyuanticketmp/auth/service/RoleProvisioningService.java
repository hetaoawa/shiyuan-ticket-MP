package top.hetao.shiyuanticketmp.auth.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import top.hetao.shiyuanticketmp.auth.entity.SysPermission;
import top.hetao.shiyuanticketmp.auth.entity.SysRole;
import top.hetao.shiyuanticketmp.auth.entity.SysRolePermission;
import top.hetao.shiyuanticketmp.auth.mapper.SysPermissionMapper;
import top.hetao.shiyuanticketmp.auth.mapper.SysRoleMapper;
import top.hetao.shiyuanticketmp.auth.mapper.SysRolePermissionMapper;
import top.hetao.shiyuanticketmp.common.context.TenantContext;
import top.hetao.shiyuanticketmp.tenant.service.TenantLifecycleGuard;

import java.util.List;
import java.util.Map;
import java.util.Set;

@Service
public class RoleProvisioningService {

    private static final Map<String, Set<String>> STANDARD_PERMISSIONS = Map.of(
            "CARGO_OWNER", Set.of("workorder:create", "workorder:view", "workorder:assign",
                    "workorder:resubmit", "workorder:comment", "workorder:export",
                    "file:upload", "file:view", "file:delete"),
            "WAREHOUSE_ADMIN", Set.of("workorder:close", "workorder:reject", "workorder:view",
                    "workorder:resubmit", "workorder:comment",
                    "file:upload", "file:view", "file:delete"));

    private final SysRoleMapper roleMapper;
    private final SysPermissionMapper permissionMapper;
    private final SysRolePermissionMapper rolePermissionMapper;
    private final TenantLifecycleGuard tenantLifecycleGuard;

    public RoleProvisioningService(SysRoleMapper roleMapper,
                                   SysPermissionMapper permissionMapper,
                                   SysRolePermissionMapper rolePermissionMapper,
                                   TenantLifecycleGuard tenantLifecycleGuard) {
        this.roleMapper = roleMapper;
        this.permissionMapper = permissionMapper;
        this.rolePermissionMapper = rolePermissionMapper;
        this.tenantLifecycleGuard = tenantLifecycleGuard;
    }

    @Transactional
    public void provisionTenantRoles(Long tenantId) {
        if (tenantId == null || tenantId <= 0) {
            throw new IllegalArgumentException("Business tenant id must be positive");
        }
        // Safe for new tenants: this transaction already owns the inserted tenant row lock.
        tenantLifecycleGuard.lockWritableTenant(tenantId);
        try (TenantContext.Scope ignored = TenantContext.useTenant(tenantId)) {
            ensureRole(tenantId, "SYSTEM_ADMIN", "系统管理员", null);
            ensureRole(tenantId, "WAREHOUSE_ADMIN", "云仓管理员", STANDARD_PERMISSIONS.get("WAREHOUSE_ADMIN"));
            ensureRole(tenantId, "CARGO_OWNER", "货主", STANDARD_PERMISSIONS.get("CARGO_OWNER"));
        }
    }

    private void ensureRole(Long tenantId, String roleCode, String roleName, Set<String> permissionCodes) {
        SysRole role = roleMapper.selectOne(new LambdaQueryWrapper<SysRole>()
                .eq(SysRole::getRoleCode, roleCode));
        if (role == null) {
            role = new SysRole();
            role.setTenantId(tenantId);
            role.setRoleCode(roleCode);
            role.setRoleName(roleName);
            roleMapper.insert(role);
        }

        List<SysPermission> permissions = permissionCodes == null
                ? permissionMapper.selectList(new LambdaQueryWrapper<>())
                : permissionMapper.selectList(new LambdaQueryWrapper<SysPermission>()
                        .in(SysPermission::getPermissionCode, permissionCodes));
        for (SysPermission permission : permissions) {
            long existing = rolePermissionMapper.selectCount(new LambdaQueryWrapper<SysRolePermission>()
                    .eq(SysRolePermission::getRoleId, role.getId())
                    .eq(SysRolePermission::getPermissionId, permission.getId()));
            if (existing == 0) {
                SysRolePermission relation = new SysRolePermission();
                relation.setRoleId(role.getId());
                relation.setPermissionId(permission.getId());
                rolePermissionMapper.insert(relation);
            }
        }
    }
}
