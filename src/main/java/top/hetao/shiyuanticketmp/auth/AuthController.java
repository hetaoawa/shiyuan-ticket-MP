package top.hetao.shiyuanticketmp.auth;

import cn.dev33.satoken.stp.StpUtil;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.*;
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
        SysUser user = userService.getByUsername(username);
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

        // 将租户 ID 写入 Sa-Token 会话，后续请求可从中提取
        StpUtil.getSession().set("tenantId", user.getTenantId());

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
    @GetMapping("/me")
    public Map<String, Object> me() {
        Long userId = StpUtil.getLoginIdAsLong();
        SysUser user = userService.getById(userId);

        Map<String, Object> result = new HashMap<>();
        result.put("code", 200);
        result.put("userId", userId);
        result.put("username", user.getUsername());
        result.put("nickname", user.getNickname());
        result.put("tenantId", user.getTenantId());
        result.put("roles", userService.getRoleCodes(userId));
        result.put("permissions", userService.getPermissionCodes(userId));
        result.put("tokenTimeout", StpUtil.getTokenTimeout());
        return result;
    }
}
