package top.hetao.shiyuanticketmp.auth.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import org.springframework.web.bind.annotation.*;
import top.hetao.shiyuanticketmp.auth.controller.dto.AssignPermissionsRequest;
import top.hetao.shiyuanticketmp.auth.controller.dto.CreateRoleRequest;
import top.hetao.shiyuanticketmp.auth.controller.dto.UpdateRoleRequest;
import top.hetao.shiyuanticketmp.auth.entity.SysPermission;
import top.hetao.shiyuanticketmp.auth.entity.SysRole;
import top.hetao.shiyuanticketmp.auth.service.RoleService;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/admin/roles")
public class RoleController {

    private final RoleService roleService;

    public RoleController(RoleService roleService) {
        this.roleService = roleService;
    }

    @SaCheckPermission("role:view")
    @GetMapping
    public Map<String, Object> list() {
        List<SysRole> roles = roleService.listAll();
        Map<String, Object> response = new HashMap<>();
        response.put("code", 200);
        response.put("data", roles);
        return response;
    }

    @SaCheckPermission("role:view")
    @GetMapping("/{id}")
    public Map<String, Object> detail(@PathVariable Long id) {
        SysRole role = roleService.getById(id);
        Map<String, Object> response = new HashMap<>();
        response.put("code", 200);
        response.put("data", role);
        return response;
    }

    @SaCheckPermission("role:create")
    @PostMapping
    public Map<String, Object> create(@RequestBody CreateRoleRequest request) {
        SysRole role = roleService.createRole(request);
        Map<String, Object> response = new HashMap<>();
        response.put("code", 200);
        response.put("message", "角色创建成功");
        response.put("data", role.getId());
        return response;
    }

    @SaCheckPermission("role:update")
    @PutMapping("/{id}")
    public Map<String, Object> update(@PathVariable Long id, @RequestBody UpdateRoleRequest request) {
        roleService.updateRole(id, request);
        Map<String, Object> response = new HashMap<>();
        response.put("code", 200);
        response.put("message", "角色更新成功");
        return response;
    }

    @SaCheckPermission("role:delete")
    @DeleteMapping("/{id}")
    public Map<String, Object> delete(@PathVariable Long id) {
        SysRole role = roleService.getById(id);
        if (role == null) {
            throw new top.hetao.shiyuanticketmp.workorder.exception.WorkOrderException("角色不存在: " + id);
        }
        roleService.removeById(id);
        Map<String, Object> response = new HashMap<>();
        response.put("code", 200);
        response.put("message", "角色删除成功");
        return response;
    }

    @SaCheckPermission("role:view")
    @GetMapping("/{id}/permissions")
    public Map<String, Object> getRolePermissions(@PathVariable Long id) {
        Map<String, Object> response = new HashMap<>();
        response.put("code", 200);
        response.put("data", roleService.getRolePermissionIds(id));
        return response;
    }

    @SaCheckPermission("role:update")
    @PostMapping("/{id}/permissions")
    public Map<String, Object> assignPermissions(@PathVariable Long id, @RequestBody AssignPermissionsRequest request) {
        roleService.assignPermissions(id, request.getPermissionIds());
        Map<String, Object> response = new HashMap<>();
        response.put("code", 200);
        response.put("message", "权限分配成功");
        return response;
    }

    @SaCheckPermission("role:view")
    @GetMapping("/permissions/all")
    public Map<String, Object> listAllPermissions() {
        List<SysPermission> permissions = roleService.listAllPermissions();
        Map<String, Object> response = new HashMap<>();
        response.put("code", 200);
        response.put("data", permissions);
        return response;
    }
}
