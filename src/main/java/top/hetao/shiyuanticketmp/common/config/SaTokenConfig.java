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
import top.hetao.shiyuanticketmp.auth.exception.InvalidAuthSessionException;
import top.hetao.shiyuanticketmp.auth.service.AuthTenantService;
import top.hetao.shiyuanticketmp.auth.service.UserService;
import top.hetao.shiyuanticketmp.common.context.TenantContext;
import top.hetao.shiyuanticketmp.workorder.exception.WorkOrderException;

import java.util.List;

/**
 * Sa-Token 全局配置。
 *
 * <p>职责：
 * <ol>
 *   <li>注册 Sa-Token 拦截器，对所有接口进行登录校验（排除白名单路径）</li>
 *   <li>注册租户上下文拦截器，从 Sa-Token 会话中提取 tenantId 写入 TenantContext</li>
 *   <li>提供 StpInterface 实现，从数据库查询真实角色和权限</li>
 * </ol>
 */
@Configuration
public class SaTokenConfig implements WebMvcConfigurer {

    private final AuthTenantService authTenantService;

    public SaTokenConfig(AuthTenantService authTenantService) {
        this.authTenantService = authTenantService;
    }

    /** Sa-Token 登录校验排除路径 */
    private static final String[] AUTH_EXCLUDE_PATHS = {
            "/api/auth/login",
            "/api/auth/tenant-options",
            "/api/webhook",
            "/api/webhook/cargo-owner",
            "/api/platform/ssl/deploy/import",
            "/error",
            "/favicon.ico"
    };

    /** 租户上下文拦截器排除路径（已停用租户的既有会话仍应能正常登出） */
    private static final String[] TENANT_EXCLUDE_PATHS = {
            "/api/auth/login",
            "/api/auth/tenant-options",
            "/api/auth/logout",
            "/api/webhook",
            "/api/webhook/cargo-owner",
            "/api/platform/ssl/deploy/import",
            "/error",
            "/favicon.ico"
    };

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        // 必须先建立租户上下文，随后执行的鉴权注解及角色/权限查询才能受到租户隔离。
        registry.addInterceptor(new TenantInterceptor(authTenantService))
                .addPathPatterns("/**")
                .excludePathPatterns(TENANT_EXCLUDE_PATHS)
                .order(-1);

        // Sa-Token 登录校验拦截器
        registry.addInterceptor(new SaInterceptor(handle -> {
                    StpUtil.checkLogin();
                }))
                .addPathPatterns("/**")
                .excludePathPatterns(AUTH_EXCLUDE_PATHS)
                .order(0);
    }

    /**
     * 租户上下文拦截器，从 Sa-Token 会话中提取 tenantId 写入 TenantContext。
     *
     * <p>请求开始时设置，请求结束时清除，防止 ThreadLocal 泄漏。
     */
    public static class TenantInterceptor implements HandlerInterceptor {

        private static final String SCOPE_ATTRIBUTE = TenantInterceptor.class.getName() + ".scope";
        private final AuthTenantService authTenantService;

        public TenantInterceptor(AuthTenantService authTenantService) {
            this.authTenantService = authTenantService;
        }

        @Override
        public boolean preHandle(HttpServletRequest request,
                                 HttpServletResponse response,
                                 Object handler) {
            TenantContext.clear();
            if (!StpUtil.isLogin()) {
                return true;
            }
            try {
                var context = authTenantService.requireSessionContext(
                        StpUtil.getLoginId(), StpUtil.getSession());
                if (context.activeTenantId() == null) {
                    if (isTenantNeutral(request.getRequestURI())) {
                        return true;
                    }
                    throw new WorkOrderException("请先选择租户");
                }
                TenantContext.Scope scope = TenantContext.useTenant(context.activeTenantId());
                request.setAttribute(SCOPE_ATTRIBUTE, scope);
                return true;
            } catch (InvalidAuthSessionException e) {
                StpUtil.logout();
                throw e;
            }
        }

        private static boolean isTenantNeutral(String uri) {
            if (uri != null && uri.startsWith("/api/admin/platform/ssl")) {
                return true;
            }
            if ("/api/auth/me".equals(uri)
                    || "/api/auth/switch-tenant".equals(uri)
                    || "/api/auth/password".equals(uri)
                    || "/api/auth/profile".equals(uri)
                    || "/api/system/version".equals(uri)) {
                return true;
            }
            if ("/api/admin/tenants".equals(uri) || "/api/admin/tenants/options".equals(uri)) {
                return true;
            }
            return uri != null && uri.matches("/api/admin/tenants/\\d+");
        }

        @Override
        public void afterCompletion(HttpServletRequest request,
                                    HttpServletResponse response,
                                    Object handler, Exception ex) {
            Object scope = request.getAttribute(SCOPE_ATTRIBUTE);
            if (scope instanceof TenantContext.Scope tenantScope) {
                tenantScope.close();
            } else {
                TenantContext.clear();
            }
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
