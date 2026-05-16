package top.hetao.shiyuanticketmp.common.config;

import cn.dev33.satoken.interceptor.SaInterceptor;
import cn.dev33.satoken.stp.StpInterface;
import cn.dev33.satoken.stp.StpUtil;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import top.hetao.shiyuanticketmp.auth.service.UserService;
import top.hetao.shiyuanticketmp.common.context.TenantContext;

import java.util.List;

/**
 * Sa-Token 全局配置。
 *
 * <p>职责：
 * <ol>
 *   <li>注册内部签名校验拦截器（最先执行，校验前端请求头）</li>
 *   <li>注册 Sa-Token 拦截器，对所有接口进行登录校验（排除白名单路径）</li>
 *   <li>注册租户上下文拦截器，从 Sa-Token 会话中提取 tenantId 写入 TenantContext</li>
 *   <li>提供 StpInterface 实现，从数据库查询真实角色和权限</li>
 * </ol>
 */
@Configuration
public class SaTokenConfig implements WebMvcConfigurer {

    /** Sa-Token 登录校验排除路径 */
    private static final String[] AUTH_EXCLUDE_PATHS = {
            "/api/auth/**",
            "/api/webhook",
            "/api/webhook/**",
            "/error",
            "/favicon.ico"
    };

    /** 租户上下文拦截器排除路径（仅排除登录，/api/auth/me 和 /api/auth/logout 需要租户上下文） */
    private static final String[] TENANT_EXCLUDE_PATHS = {
            "/api/auth/login",
            "/api/webhook",
            "/api/webhook/**",
            "/error",
            "/favicon.ico"
    };

    private final InternalSignatureInterceptor internalSignatureInterceptor;

    public SaTokenConfig(InternalSignatureInterceptor internalSignatureInterceptor) {
        this.internalSignatureInterceptor = internalSignatureInterceptor;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        // 内部签名校验拦截器（最先执行，order=-1）
        registry.addInterceptor(internalSignatureInterceptor)
                .addPathPatterns("/api/**")
                .excludePathPatterns(
                        "/api/webhook",
                        "/api/webhook/**",
                        "/error",
                        "/favicon.ico"
                )
                .order(-1);

        // Sa-Token 登录校验拦截器
        registry.addInterceptor(new SaInterceptor(handle -> {
                    // checkLogin 会校验当前请求是否已登录
                }))
                .addPathPatterns("/**")
                .excludePathPatterns(AUTH_EXCLUDE_PATHS);

        // 租户上下文拦截器（在 Sa-Token 之后执行，确保已登录）
        registry.addInterceptor(new TenantInterceptor())
                .addPathPatterns("/**")
                .excludePathPatterns(TENANT_EXCLUDE_PATHS)
                .order(1);
    }

    /**
     * 租户上下文拦截器，从 Sa-Token 会话中提取 tenantId 写入 TenantContext。
     *
     * <p>请求开始时设置，请求结束时清除，防止 ThreadLocal 泄漏。
     */
    public static class TenantInterceptor implements HandlerInterceptor {

        @Override
        public boolean preHandle(HttpServletRequest request,
                                 HttpServletResponse response,
                                 Object handler) {
            try {
                Object tenantId = StpUtil.getSession().get("tenantId");
                if (tenantId != null) {
                    TenantContext.setTenantId(Long.parseLong(tenantId.toString()));
                }
                // 从 Session 读取登录时存入的超管标志，避免调用 getRoleList() 触发租户过滤的 SQL
                Object isAdmin = StpUtil.getSession().get("isAdmin");
                TenantContext.setAdmin(isAdmin instanceof Boolean && (Boolean) isAdmin);
            } catch (Exception ignored) {
                // 未登录时获取 session 会抛异常，忽略即可
            }
            return true;
        }

        @Override
        public void afterCompletion(HttpServletRequest request,
                                    HttpServletResponse response,
                                    Object handler, Exception ex) {
            TenantContext.clear();
        }
    }

    /**
     * Sa-Token 权限数据源实现，从数据库查询真实角色和权限。
     */
    @Configuration
    public static class StpInterfaceImpl implements StpInterface {

        private final UserService userService;

        public StpInterfaceImpl(UserService userService) {
            this.userService = userService;
        }

        @Override
        public List<String> getPermissionList(Object loginId, String loginType) {
            return userService.getPermissionCodes(Long.parseLong(loginId.toString()));
        }

        @Override
        public List<String> getRoleList(Object loginId, String loginType) {
            return userService.getRoleCodes(Long.parseLong(loginId.toString()));
        }
    }
}
