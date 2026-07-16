package top.hetao.shiyuanticketmp.auth;

import cn.dev33.satoken.annotation.SaCheckLogin;
import cn.dev33.satoken.stp.StpUtil;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.*;
import top.hetao.shiyuanticketmp.auth.controller.dto.ChangePasswordRequest;
import top.hetao.shiyuanticketmp.auth.controller.dto.SwitchTenantRequest;
import top.hetao.shiyuanticketmp.auth.entity.SysUser;
import top.hetao.shiyuanticketmp.auth.service.AuthTenantService;
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
    private final AuthTenantService authTenantService;

    public AuthController(UserService userService,
                          PasswordEncoder passwordEncoder,
                          AuthTenantService authTenantService) {
        this.userService = userService;
        this.passwordEncoder = passwordEncoder;
        this.authTenantService = authTenantService;
    }

    /**
     * 登录接口（用户名 + 密码）。
     *
     * @param username 用户名
     * @param password 密码（BCrypt 加密校验）
     * @return 包含 token 的响应
     */
    @PostMapping("/login")
    public Map<String, Object> login(@RequestParam String tenantCode,
                                     @RequestParam String username,
                                     @RequestParam String password) {
        AuthTenantService.ResolvedLogin resolved = authTenantService.resolveLogin(tenantCode, username);
        SysUser user = resolved.user();

        if (!passwordEncoder.matches(password, user.getPassword())) {
            throw new WorkOrderException("用户名或密码错误");
        }

        if (user.getStatus() != 1) {
            throw new WorkOrderException("账号已被禁用");
        }

        // Sa-Token 登录，loginId 使用用户 ID
        StpUtil.login(user.getId());
        AuthTenantContext context = authTenantService.initializeSession(resolved);

        Map<String, Object> result = new HashMap<>();
        result.put("code", 200);
        result.put("message", "登录成功");
        result.put("token", StpUtil.getTokenValue());
        result.put("userId", user.getId());
        result.put("username", user.getUsername());
        result.put("tenantId", context.activeTenantId());
        putContext(result, context);
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
        if (user == null) {
            throw new WorkOrderException("用户不存在");
        }
        AuthTenantContext context = authTenantService.readContext();

        Map<String, Object> result = new HashMap<>();
        result.put("code", 200);
        result.put("userId", userId);
        result.put("username", user.getUsername());
        result.put("nickname", user.getNickname());
        result.put("phone", user.getPhone());
        result.put("email", user.getEmail());
        result.put("externalUserId", user.getExternalUserId());
        result.put("tenantId", context.activeTenantId());
        putContext(result, context);
        result.put("roles", userService.getRoleCodes(userId));
        result.put("permissions", userService.getPermissionCodes(userId));
        result.put("tokenTimeout", StpUtil.getTokenTimeout());
        return result;
    }

    @SaCheckLogin
    @PostMapping("/switch-tenant")
    public Map<String, Object> switchTenant(@RequestBody SwitchTenantRequest request) {
        if (request == null) {
            throw new WorkOrderException("租户参数不能为空");
        }
        AuthTenantContext context = authTenantService.switchTenant(request.getTenantId());
        Map<String, Object> result = new HashMap<>();
        result.put("code", 200);
        result.put("message", "租户切换成功");
        putContext(result, context);
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

        userService.resetPrincipalPassword(userId, request.getNewPassword());

        Map<String, Object> result = new HashMap<>();
        result.put("code", 200);
        result.put("message", "密码修改成功");
        return result;
    }

    /**
     * 更新当前登录用户个人信息（昵称、手机号、邮箱）。
     *
     * <p>使用 LambdaUpdateWrapper 显式 SET，确保空字符串清除的 null 值能持久化到数据库，
     * 绕过 MyBatis-Plus updateById 默认跳过 null 字段的策略。
     */
    @SaCheckLogin
    @PutMapping("/profile")
    public Map<String, Object> updateProfile(@RequestBody Map<String, String> body) {
        Long userId = StpUtil.getLoginIdAsLong();
        SysUser user = userService.getByIdIgnoreTenant(userId);
        if (user == null) {
            throw new WorkOrderException("用户不存在");
        }

        userService.updatePrincipalProfile(userId, body);

        Map<String, Object> result = new HashMap<>();
        result.put("code", 200);
        result.put("message", "个人信息更新成功");
        return result;
    }

    private static void putContext(Map<String, Object> result, AuthTenantContext context) {
        result.put("principalTenantId", context.principalTenantId());
        result.put("activeTenantId", context.activeTenantId());
        result.put("activeTenantCode", context.activeTenantCode());
        result.put("activeTenantName", context.activeTenantName());
        result.put("globalAdmin", context.globalAdmin());
    }
}
