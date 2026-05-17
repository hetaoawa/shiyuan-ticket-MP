package top.hetao.shiyuanticketmp.auth;

import cn.dev33.satoken.annotation.SaCheckLogin;
import cn.dev33.satoken.stp.StpUtil;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.*;
import top.hetao.shiyuanticketmp.auth.controller.dto.ChangePasswordRequest;
import top.hetao.shiyuanticketmp.auth.entity.SysUser;
import top.hetao.shiyuanticketmp.auth.service.UserService;
import top.hetao.shiyuanticketmp.workorder.exception.WorkOrderException;

import java.util.HashMap;
import java.util.Map;

/**
 * 认证控制器，提供登录/登出/当前用户信息接口。
 */
@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final UserService userService;
    private final PasswordEncoder passwordEncoder;

    public AuthController(UserService userService, PasswordEncoder passwordEncoder) {
        this.userService = userService;
        this.passwordEncoder = passwordEncoder;
    }

    /**
     * 登录接口（用户名 + 密码）。
     *
     * @param username 用户名
     * @param password 密码（BCrypt 加密校验）
     * @return 包含 token 的响应
     */
    @PostMapping("/login")
    public Map<String, Object> login(@RequestParam String username,
                                     @RequestParam String password) {
        SysUser user = userService.getByUsernameIgnoreTenant(username);
        if (user == null) {
            throw new WorkOrderException("用户名或密码错误");
        }

        if (!passwordEncoder.matches(password, user.getPassword())) {
            throw new WorkOrderException("用户名或密码错误");
        }

        if (user.getStatus() != 1) {
            throw new WorkOrderException("账号已被禁用");
        }

        // Sa-Token 登录，loginId 使用用户 ID
        StpUtil.login(user.getId());

        // 将租户 ID 和超管标志写入 Sa-Token 会话
        StpUtil.getSession().set("tenantId", user.getTenantId());
        boolean isAdmin = userService.getRoleCodes(user.getId()).contains("SYSTEM_ADMIN");
        StpUtil.getSession().set("isAdmin", isAdmin);

        Map<String, Object> result = new HashMap<>();
        result.put("code", 200);
        result.put("message", "登录成功");
        result.put("token", StpUtil.getTokenValue());
        result.put("userId", user.getId());
        result.put("username", user.getUsername());
        result.put("tenantId", user.getTenantId());
        return result;
    }

    /**
     * 登出接口。
     */
    @SaCheckLogin
    @PostMapping("/logout")
    public Map<String, Object> logout() {
        StpUtil.logout();
        Map<String, Object> result = new HashMap<>();
        result.put("code", 200);
        result.put("message", "登出成功");
        return result;
    }

    /**
     * 获取当前登录用户信息。
     */
    @SaCheckLogin
    @GetMapping("/me")
    public Map<String, Object> me() {
        Long userId = StpUtil.getLoginIdAsLong();
        SysUser user = userService.getByIdIgnoreTenant(userId);

        Map<String, Object> result = new HashMap<>();
        result.put("code", 200);
        result.put("userId", userId);
        result.put("username", user.getUsername());
        result.put("nickname", user.getNickname());
        result.put("phone", user.getPhone());
        result.put("email", user.getEmail());
        result.put("externalUserId", user.getExternalUserId());
        result.put("tenantId", user.getTenantId());
        result.put("roles", userService.getRoleCodes(userId));
        result.put("permissions", userService.getPermissionCodes(userId));
        result.put("tokenTimeout", StpUtil.getTokenTimeout());
        return result;
    }

    /**
     * 修改当前登录用户密码。
     *
     * @param request 包含旧密码和新密码
     * @return 成功响应
     */
    @SaCheckLogin
    @PutMapping("/password")
    public Map<String, Object> changePassword(@RequestBody ChangePasswordRequest request) {
        if (request.getOldPassword() == null || request.getOldPassword().isBlank()) {
            throw new WorkOrderException("旧密码不能为空");
        }
        if (request.getNewPassword() == null || request.getNewPassword().isBlank()) {
            throw new WorkOrderException("新密码不能为空");
        }

        Long userId = StpUtil.getLoginIdAsLong();
        SysUser user = userService.getByIdIgnoreTenant(userId);
        if (user == null) {
            throw new WorkOrderException("用户不存在");
        }

        if (!passwordEncoder.matches(request.getOldPassword(), user.getPassword())) {
            throw new WorkOrderException("旧密码不正确");
        }

        userService.resetPassword(userId, request.getNewPassword());

        Map<String, Object> result = new HashMap<>();
        result.put("code", 200);
        result.put("message", "密码修改成功");
        return result;
    }

    /**
     * 更新当前登录用户个人信息（昵称、手机号、邮箱）。
     */
    @SaCheckLogin
    @PutMapping("/profile")
    public Map<String, Object> updateProfile(@RequestBody Map<String, String> body) {
        Long userId = StpUtil.getLoginIdAsLong();
        SysUser user = userService.getByIdIgnoreTenant(userId);
        if (user == null) {
            throw new WorkOrderException("用户不存在");
        }

        if (body.containsKey("nickname") && body.get("nickname") != null) {
            user.setNickname(body.get("nickname"));
        }
        if (body.containsKey("phone")) {
            user.setPhone(body.get("phone"));
        }
        if (body.containsKey("email")) {
            user.setEmail(body.get("email"));
        }
        userService.updateById(user);

        Map<String, Object> result = new HashMap<>();
        result.put("code", 200);
        result.put("message", "个人信息更新成功");
        return result;
    }
}
