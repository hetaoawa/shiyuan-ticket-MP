package top.hetao.shiyuanticketmp.auth.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import com.baomidou.mybatisplus.core.metadata.IPage;
import org.springframework.web.bind.annotation.*;
import top.hetao.shiyuanticketmp.auth.controller.dto.AssignRolesRequest;
import top.hetao.shiyuanticketmp.auth.controller.dto.CreateUserRequest;
import top.hetao.shiyuanticketmp.auth.controller.dto.UpdateUserRequest;
import top.hetao.shiyuanticketmp.auth.entity.SysUser;
import top.hetao.shiyuanticketmp.auth.service.UserService;

import java.util.HashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/admin/users")
public class UserController {

    private final UserService userService;

    public UserController(UserService userService) {
        this.userService = userService;
    }

    @SaCheckPermission("user:view")
    @GetMapping
    public Map<String, Object> list(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "10") int pageSize,
            @RequestParam(required = false) String username,
            @RequestParam(required = false) Integer status) {
        IPage<SysUser> result = userService.listPage(page, pageSize, username, status);
        Map<String, Object> response = new HashMap<>();
        response.put("code", 200);
        response.put("data", result);
        return response;
    }

    @SaCheckPermission("user:view")
    @GetMapping("/{id}")
    public Map<String, Object> detail(@PathVariable Long id) {
        SysUser user = userService.getById(id);
        Map<String, Object> response = new HashMap<>();
        response.put("code", 200);
        response.put("data", user);
        return response;
    }

    @SaCheckPermission("user:create")
    @PostMapping
    public Map<String, Object> create(@RequestBody CreateUserRequest request) {
        SysUser user = userService.createUser(request);
        Map<String, Object> response = new HashMap<>();
        response.put("code", 200);
        response.put("message", "用户创建成功");
        response.put("data", user.getId());
        return response;
    }

    @SaCheckPermission("user:update")
    @PutMapping("/{id}")
    public Map<String, Object> update(@PathVariable Long id, @RequestBody UpdateUserRequest request) {
        userService.updateUser(id, request);
        Map<String, Object> response = new HashMap<>();
        response.put("code", 200);
        response.put("message", "用户更新成功");
        return response;
    }

    @SaCheckPermission("user:delete")
    @DeleteMapping("/{id}")
    public Map<String, Object> delete(@PathVariable Long id) {
        userService.removeById(id);
        Map<String, Object> response = new HashMap<>();
        response.put("code", 200);
        response.put("message", "用户删除成功");
        return response;
    }

    @SaCheckPermission("user:update")
    @PostMapping("/{id}/reset-password")
    public Map<String, Object> resetPassword(@PathVariable Long id, @RequestParam String newPassword) {
        userService.resetPassword(id, newPassword);
        Map<String, Object> response = new HashMap<>();
        response.put("code", 200);
        response.put("message", "密码重置成功");
        return response;
    }

    @SaCheckPermission("user:update")
    @GetMapping("/{id}/roles")
    public Map<String, Object> getUserRoles(@PathVariable Long id) {
        Map<String, Object> response = new HashMap<>();
        response.put("code", 200);
        response.put("data", userService.getUserRoleIds(id));
        return response;
    }

    @SaCheckPermission("user:update")
    @PostMapping("/{id}/roles")
    public Map<String, Object> assignRoles(@PathVariable Long id, @RequestBody AssignRolesRequest request) {
        userService.assignRoles(id, request.getRoleIds());
        Map<String, Object> response = new HashMap<>();
        response.put("code", 200);
        response.put("message", "角色分配成功");
        return response;
    }
}
