package top.hetao.shiyuanticketmp.auth.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import top.hetao.shiyuanticketmp.auth.controller.dto.CreateRoleRequest;
import top.hetao.shiyuanticketmp.auth.controller.dto.UpdateRoleRequest;
import top.hetao.shiyuanticketmp.auth.entity.SysRole;
import top.hetao.shiyuanticketmp.auth.entity.SysRolePermission;
import top.hetao.shiyuanticketmp.auth.mapper.SysRoleMapper;
import top.hetao.shiyuanticketmp.auth.mapper.SysRolePermissionMapper;
import top.hetao.shiyuanticketmp.workorder.exception.WorkOrderException;

import java.util.List;

@Service
public class RoleService extends ServiceImpl<SysRoleMapper, SysRole> {

    private final SysRolePermissionMapper rolePermissionMapper;

    public RoleService(SysRolePermissionMapper rolePermissionMapper) {
        this.rolePermissionMapper = rolePermissionMapper;
    }

    @Transactional(readOnly = true)
    public List<SysRole> listAll() {
        return list(new LambdaQueryWrapper<SysRole>().orderByAsc(SysRole::getId));
    }

    @Transactional
    public SysRole createRole(CreateRoleRequest request) {
        if (request.getRoleCode() == null || request.getRoleCode().isBlank()) {
            throw new WorkOrderException("角色编码不能为空");
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
        SysRole role = getById(roleId);
        if (role == null) {
            throw new WorkOrderException("角色不存在: " + roleId);
        }
        if (request.getRoleName() != null) {
            role.setRoleName(request.getRoleName());
        }
        updateById(role);
    }

    @Transactional
    public void assignPermissions(Long roleId, List<Long> permissionIds) {
        SysRole role = getById(roleId);
        if (role == null) {
            throw new WorkOrderException("角色不存在: " + roleId);
        }
        rolePermissionMapper.delete(new LambdaQueryWrapper<SysRolePermission>()
                .eq(SysRolePermission::getRoleId, roleId));
        for (Long permissionId : permissionIds) {
            SysRolePermission rp = new SysRolePermission();
            rp.setRoleId(roleId);
            rp.setPermissionId(permissionId);
            rolePermissionMapper.insert(rp);
        }
    }

    @Transactional(readOnly = true)
    public List<Long> getRolePermissionIds(Long roleId) {
        return rolePermissionMapper.selectPermissionIdsByRoleId(roleId);
    }
}
