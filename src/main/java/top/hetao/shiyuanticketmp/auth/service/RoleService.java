package top.hetao.shiyuanticketmp.auth.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import top.hetao.shiyuanticketmp.auth.controller.dto.CreateRoleRequest;
import top.hetao.shiyuanticketmp.auth.controller.dto.UpdateRoleRequest;
import top.hetao.shiyuanticketmp.auth.entity.SysPermission;
import top.hetao.shiyuanticketmp.auth.entity.SysRole;
import top.hetao.shiyuanticketmp.auth.entity.SysRolePermission;
import top.hetao.shiyuanticketmp.auth.mapper.SysPermissionMapper;
import top.hetao.shiyuanticketmp.auth.mapper.SysRoleMapper;
import top.hetao.shiyuanticketmp.auth.mapper.SysRolePermissionMapper;
import top.hetao.shiyuanticketmp.common.context.TenantContext;
import top.hetao.shiyuanticketmp.tenant.service.TenantLifecycleGuard;
import top.hetao.shiyuanticketmp.workorder.exception.WorkOrderException;

import java.util.List;

@Service
public class RoleService extends ServiceImpl<SysRoleMapper, SysRole> {

    private final SysRolePermissionMapper rolePermissionMapper;
    private final SysPermissionMapper permissionMapper;
    private final TenantLifecycleGuard tenantLifecycleGuard;

    public RoleService(SysRolePermissionMapper rolePermissionMapper,
                       SysPermissionMapper permissionMapper,
                       TenantLifecycleGuard tenantLifecycleGuard) {
        this.rolePermissionMapper = rolePermissionMapper;
        this.permissionMapper = permissionMapper;
        this.tenantLifecycleGuard = tenantLifecycleGuard;
    }

    @Transactional(readOnly = true)
    public List<SysRole> listAll() {
        return list(new LambdaQueryWrapper<SysRole>().orderByAsc(SysRole::getId));
    }

    @Transactional
    public SysRole createRole(CreateRoleRequest request) {
        tenantLifecycleGuard.lockWritableTenant(TenantContext.requireTenantId());
        if (request.getRoleCode() == null || request.getRoleCode().isBlank()) {
            throw new WorkOrderException("角色编码不能为空");
        }
        if (isReservedAdministratorRole(request.getRoleCode())) {
            throw new WorkOrderException("SYSTEM_ADMIN和GLOBAL_SYSTEM_ADMIN是系统保留角色");
        }
        long count = count(new LambdaQueryWrapper<SysRole>()
                .eq(SysRole::getRoleCode, request.getRoleCode()));
        if (count > 0) {
            throw new WorkOrderException("角色编码已存在: " + request.getRoleCode());
        }
        SysRole role = new SysRole();
        role.setRoleCode(request.getRoleCode());
        role.setRoleName(request.getRoleName());
        save(role);
        return role;
    }

    @Transactional
    public void updateRole(Long roleId, UpdateRoleRequest request) {
        tenantLifecycleGuard.lockWritableTenant(TenantContext.requireTenantId());
        SysRole role = getById(roleId);
        if (role == null) {
            throw new WorkOrderException("角色不存在: " + roleId);
        }
        assertMutableTenantRole(role);
        if (request.getRoleName() != null) {
            role.setRoleName(request.getRoleName());
        }
        updateById(role);
    }

    @Transactional
    public void assignPermissions(Long roleId, List<Long> permissionIds) {
        Long tenantId = TenantContext.requireTenantId();
        tenantLifecycleGuard.lockWritableTenant(tenantId);
        SysRole role = getById(roleId);
        if (role == null) {
            throw new WorkOrderException("角色不存在: " + roleId);
        }
        assertMutableTenantRole(role);
        if (permissionIds == null) {
            throw new WorkOrderException("权限ID列表不能为空，请使用空列表 [] 表示清空权限");
        }
        List<SysPermission> permissions = new java.util.ArrayList<>(permissionIds.size());
        for (Long permissionId : permissionIds) {
            SysPermission permission = permissionId == null ? null : permissionMapper.selectById(permissionId);
            if (permission == null) {
                throw new WorkOrderException("权限不存在: " + permissionId);
            }
            if (!Long.valueOf(0L).equals(tenantId) && isPlatformPermission(permission)) {
                throw new WorkOrderException("租户角色不能绑定平台权限: " + permission.getPermissionCode());
            }
            permissions.add(permission);
        }
        rolePermissionMapper.delete(new LambdaQueryWrapper<SysRolePermission>()
                .eq(SysRolePermission::getRoleId, roleId));
        for (SysPermission permission : permissions) {
            SysRolePermission rp = new SysRolePermission();
            rp.setRoleId(roleId);
            rp.setPermissionId(permission.getId());
            rolePermissionMapper.insert(rp);
        }
    }

    @Transactional(readOnly = true)
    public List<Long> getRolePermissionIds(Long roleId) {
        if (getById(roleId) == null) {
            throw new WorkOrderException("角色不存在: " + roleId);
        }
        return rolePermissionMapper.selectPermissionIdsByRoleId(roleId);
    }

    @Transactional(readOnly = true)
    public List<SysPermission> listAllPermissions() {
        List<SysPermission> permissions = permissionMapper.selectList(
                new LambdaQueryWrapper<SysPermission>().orderByAsc(SysPermission::getId));
        if (Long.valueOf(0L).equals(TenantContext.requireTenantId())) {
            return permissions;
        }
        return permissions.stream().filter(permission -> !isPlatformPermission(permission)).toList();
    }

    @Transactional
    public void deleteRole(Long roleId) {
        tenantLifecycleGuard.lockWritableTenant(TenantContext.requireTenantId());
        SysRole role = getById(roleId);
        if (role == null) {
            throw new WorkOrderException("角色不存在: " + roleId);
        }
        assertMutableTenantRole(role);
        removeById(roleId);
    }

    private static void assertMutableTenantRole(SysRole role) {
        if (Long.valueOf(0L).equals(role.getTenantId())
                || isReservedAdministratorRole(role.getRoleCode())) {
            throw new WorkOrderException("系统管理员角色由租户管理员设置功能维护，不可直接修改");
        }
    }

    private static boolean isReservedAdministratorRole(String roleCode) {
        return "SYSTEM_ADMIN".equals(roleCode) || "GLOBAL_SYSTEM_ADMIN".equals(roleCode);
    }

    private static boolean isPlatformPermission(SysPermission permission) {
        return permission.getPermissionCode() != null
                && permission.getPermissionCode().startsWith("platform:");
    }
}
