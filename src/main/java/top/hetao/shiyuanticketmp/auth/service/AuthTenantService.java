package top.hetao.shiyuanticketmp.auth.service;

import cn.dev33.satoken.session.SaSession;
import cn.dev33.satoken.stp.StpUtil;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import top.hetao.shiyuanticketmp.auth.AuthTenantContext;
import top.hetao.shiyuanticketmp.auth.entity.SysUser;
import top.hetao.shiyuanticketmp.auth.exception.InvalidAuthSessionException;
import top.hetao.shiyuanticketmp.auth.mapper.SysUserMapper;
import top.hetao.shiyuanticketmp.common.context.TenantContext;
import top.hetao.shiyuanticketmp.tenant.entity.SysTenant;
import top.hetao.shiyuanticketmp.tenant.service.TenantService;
import top.hetao.shiyuanticketmp.workorder.exception.WorkOrderException;

import java.util.Locale;

@Service
public class AuthTenantService {

    public static final String PRINCIPAL_TENANT_ID = "principalTenantId";
    public static final String ACTIVE_TENANT_ID = "activeTenantId";
    public static final String ACTIVE_TENANT_CODE = "activeTenantCode";
    public static final String ACTIVE_TENANT_NAME = "activeTenantName";
    public static final String GLOBAL_ADMIN = "globalAdmin";
    public static final String LEGACY_TENANT_ID = "tenantId";
    public static final String LEGACY_IS_ADMIN = "isAdmin";

    private final SysUserMapper userMapper;
    private final UserService userService;
    private final TenantService tenantService;

    public AuthTenantService(SysUserMapper userMapper,
                             UserService userService,
                             TenantService tenantService) {
        this.userMapper = userMapper;
        this.userService = userService;
        this.tenantService = tenantService;
    }

    @Transactional(readOnly = true)
    public ResolvedLogin resolveLogin(String tenantCode, String username) {
        String normalizedCode = normalizeTenantCode(tenantCode);
        String normalizedUsername = username == null ? "" : username.trim();
        if (normalizedUsername.isEmpty()) {
            throw new WorkOrderException("用户名或密码错误");
        }

        SysUser user;
        try (TenantContext.Scope ignored = TenantContext.useInternalBypass()) {
            user = userMapper.selectByTenantCodeAndUsername(normalizedCode, normalizedUsername);
        }
        if (user == null) {
            throw new WorkOrderException("用户名或密码错误");
        }
        SysTenant tenant = tenantService.requireEnabled(user.getTenantId());
        if (!normalizedCode.equals(tenant.getTenantCode())) {
            throw new WorkOrderException("用户名或密码错误");
        }
        boolean global = Long.valueOf(0L).equals(user.getTenantId())
                && userService.getPrincipalRoleCodes(user.getId()).contains("GLOBAL_SYSTEM_ADMIN");
        if (Long.valueOf(0L).equals(user.getTenantId()) && !global) {
            throw new WorkOrderException("平台账号未配置全局系统管理员角色");
        }
        return new ResolvedLogin(user, tenant, global);
    }

    public AuthTenantContext initializeSession(ResolvedLogin resolved) {
        return initializeSession(resolved, StpUtil.getSession());
    }

    public AuthTenantContext initializeSession(ResolvedLogin resolved, SaSession session) {
        session.delete(LEGACY_TENANT_ID);
        session.delete(LEGACY_IS_ADMIN);
        session.set(PRINCIPAL_TENANT_ID, resolved.user().getTenantId());
        session.set(GLOBAL_ADMIN, resolved.globalAdmin());
        if (resolved.globalAdmin()) {
            session.delete(ACTIVE_TENANT_ID);
            session.delete(ACTIVE_TENANT_CODE);
            session.delete(ACTIVE_TENANT_NAME);
        } else {
            session.set(ACTIVE_TENANT_ID, resolved.user().getTenantId());
            session.set(ACTIVE_TENANT_CODE, normalizeTenantCode(resolved.tenant().getTenantCode()));
            session.set(ACTIVE_TENANT_NAME, resolved.tenant().getTenantName());
        }
        return readContext(session);
    }

    public AuthTenantContext readContext() {
        return readContext(StpUtil.getSession());
    }

    public AuthTenantContext readContext(SaSession session) {
        return new AuthTenantContext(
                asLong(session.get(PRINCIPAL_TENANT_ID)),
                asLong(session.get(ACTIVE_TENANT_ID)),
                asString(session.get(ACTIVE_TENANT_CODE)),
                asString(session.get(ACTIVE_TENANT_NAME)),
                Boolean.TRUE.equals(session.get(GLOBAL_ADMIN)));
    }

    /** Validates current tenant identity and migrates legacy tenant sessions. */
    @Transactional(readOnly = true)
    public AuthTenantContext requireSessionContext(Object loginId, SaSession session) {
        if (session == null) {
            throw invalidSession("Session is missing");
        }
        if (session.get(PRINCIPAL_TENANT_ID) == null) {
            return migrateLegacySession(loginId, session);
        }

        try {
            Long principalTenantId = asLong(session.get(PRINCIPAL_TENANT_ID));
            boolean globalAdmin = requireBoolean(session.get(GLOBAL_ADMIN));
            if (principalTenantId == null || principalTenantId < 0
                    || globalAdmin != Long.valueOf(0L).equals(principalTenantId)) {
                throw invalidSession("Invalid session principal");
            }

            SysTenant principalTenant = tenantService.requireEnabled(principalTenantId);
            Long activeTenantId = asLong(session.get(ACTIVE_TENANT_ID));
            if (activeTenantId == null) {
                if (!globalAdmin) {
                    throw invalidSession("Active tenant is missing");
                }
                clearActiveTenant(session);
                return readContext(session);
            }
            if (activeTenantId <= 0 || (!globalAdmin && !activeTenantId.equals(principalTenantId))) {
                throw invalidSession("Invalid active tenant");
            }
            SysTenant activeTenant = globalAdmin
                    ? tenantService.requireEnabled(activeTenantId) : principalTenant;
            storeActiveTenant(session, activeTenant);
            return readContext(session);
        } catch (InvalidAuthSessionException e) {
            throw e;
        } catch (RuntimeException e) {
            throw invalidSession("Invalid session tenant", e);
        }
    }

    private AuthTenantContext migrateLegacySession(Object loginId, SaSession session) {
        try {
            Long userId = asLong(loginId);
            Long legacyTenantId = asLong(session.get(LEGACY_TENANT_ID));
            if (userId == null || legacyTenantId == null) {
                throw invalidSession("Incomplete legacy session");
            }

            SysUser user;
            try (TenantContext.Scope ignored = TenantContext.useInternalBypass()) {
                user = userMapper.selectByIdIgnoreTenant(userId);
            }
            if (user == null || !Integer.valueOf(1).equals(user.getStatus())
                    || user.getTenantId() == null || !user.getTenantId().equals(legacyTenantId)) {
                throw invalidSession("Invalid legacy session user");
            }

            SysTenant principalTenant = tenantService.requireEnabled(user.getTenantId());
            boolean globalAdmin = Long.valueOf(0L).equals(user.getTenantId())
                    && userService.getPrincipalRoleCodes(userId).contains("GLOBAL_SYSTEM_ADMIN");
            if (Long.valueOf(0L).equals(user.getTenantId()) && !globalAdmin) {
                throw invalidSession("Invalid platform session role");
            }

            session.set(PRINCIPAL_TENANT_ID, user.getTenantId());
            session.set(GLOBAL_ADMIN, globalAdmin);
            if (globalAdmin) {
                clearActiveTenant(session);
            } else {
                storeActiveTenant(session, principalTenant);
            }
            session.delete(LEGACY_TENANT_ID);
            session.delete(LEGACY_IS_ADMIN);
            return readContext(session);
        } catch (InvalidAuthSessionException e) {
            throw e;
        } catch (RuntimeException e) {
            throw invalidSession("Legacy session migration failed", e);
        }
    }

    @Transactional(readOnly = true)
    public AuthTenantContext switchTenant(Long tenantId) {
        return switchTenant(tenantId, StpUtil.getSession());
    }

    public AuthTenantContext switchTenant(Long tenantId, SaSession session) {
        AuthTenantContext current = readContext(session);
        if (!current.globalAdmin() || !Long.valueOf(0L).equals(current.principalTenantId())) {
            throw new WorkOrderException("仅全局系统管理员可以切换租户");
        }
        if (tenantId == null || tenantId <= 0) {
            throw new WorkOrderException("请选择有效的业务租户");
        }
        SysTenant target = tenantService.requireEnabled(tenantId);
        storeActiveTenant(session, target);
        return readContext(session);
    }

    private static void storeActiveTenant(SaSession session, SysTenant tenant) {
        session.set(ACTIVE_TENANT_ID, tenant.getId());
        session.set(ACTIVE_TENANT_CODE, normalizeTenantCode(tenant.getTenantCode()));
        session.set(ACTIVE_TENANT_NAME, tenant.getTenantName());
    }

    private static void clearActiveTenant(SaSession session) {
        session.delete(ACTIVE_TENANT_ID);
        session.delete(ACTIVE_TENANT_CODE);
        session.delete(ACTIVE_TENANT_NAME);
    }

    public static String normalizeTenantCode(String tenantCode) {
        String normalized = tenantCode == null ? "" : tenantCode.trim().toLowerCase(Locale.ROOT);
        if (normalized.isEmpty()) {
            throw new WorkOrderException("租户编码不能为空");
        }
        return normalized;
    }

    private static Long asLong(Object value) {
        return value == null ? null : Long.valueOf(value.toString());
    }

    private static String asString(Object value) {
        return value == null ? null : value.toString();
    }

    private static boolean requireBoolean(Object value) {
        if (!(value instanceof Boolean booleanValue)) {
            throw invalidSession("Invalid global administrator marker");
        }
        return booleanValue;
    }

    private static InvalidAuthSessionException invalidSession(String message) {
        return new InvalidAuthSessionException(message);
    }

    private static InvalidAuthSessionException invalidSession(String message, Throwable cause) {
        return new InvalidAuthSessionException(message, cause);
    }

    public record ResolvedLogin(SysUser user, SysTenant tenant, boolean globalAdmin) {
    }
}
