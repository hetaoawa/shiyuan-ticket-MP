package top.hetao.shiyuanticketmp.auth.service;

import cn.dev33.satoken.session.SaSession;
import org.junit.jupiter.api.Test;
import top.hetao.shiyuanticketmp.auth.AuthController;
import top.hetao.shiyuanticketmp.auth.AuthTenantContext;
import top.hetao.shiyuanticketmp.auth.entity.SysUser;
import top.hetao.shiyuanticketmp.auth.mapper.SysUserMapper;
import top.hetao.shiyuanticketmp.tenant.entity.SysTenant;
import top.hetao.shiyuanticketmp.tenant.service.TenantService;

import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AuthTenantServiceContextTest {

    @Test
    void normalLoginStoresActiveTenantCodeAndReturnsIt() {
        AuthTenantService service = newService(mock(TenantService.class));
        SaSession session = new SaSession("normal-login");
        SysUser user = user(100L);
        SysTenant tenant = tenant(100L, " AcMe ", "Acme");

        AuthTenantContext context = service.initializeSession(
                new AuthTenantService.ResolvedLogin(user, tenant, false), session);

        assertEquals("acme", session.get(AuthTenantService.ACTIVE_TENANT_CODE));
        assertEquals(new AuthTenantContext(100L, 100L, "acme", "Acme", false), context);
    }

    @Test
    void globalLoginClearsActiveTenantIdentity() {
        AuthTenantService service = newService(mock(TenantService.class));
        SaSession session = new SaSession("global-login");
        session.set(AuthTenantService.ACTIVE_TENANT_ID, 100L);
        session.set(AuthTenantService.ACTIVE_TENANT_CODE, "acme");
        session.set(AuthTenantService.ACTIVE_TENANT_NAME, "Acme");
        SysUser user = user(0L);
        SysTenant tenant = tenant(0L, "platform", "Platform");

        AuthTenantContext context = service.initializeSession(
                new AuthTenantService.ResolvedLogin(user, tenant, true), session);

        assertNull(session.get(AuthTenantService.ACTIVE_TENANT_ID));
        assertNull(session.get(AuthTenantService.ACTIVE_TENANT_CODE));
        assertNull(session.get(AuthTenantService.ACTIVE_TENANT_NAME));
        assertEquals(new AuthTenantContext(0L, null, null, null, true), context);
    }

    @Test
    void switchTenantStoresActiveTenantIdCodeAndNameTogether() {
        TenantService tenantService = mock(TenantService.class);
        AuthTenantService service = newService(tenantService);
        SaSession session = new SaSession("switch-tenant");
        session.set(AuthTenantService.PRINCIPAL_TENANT_ID, 0L);
        session.set(AuthTenantService.GLOBAL_ADMIN, true);
        SysTenant target = tenant(100L, "acme", "Acme");
        when(tenantService.requireEnabled(100L)).thenReturn(target);

        AuthTenantContext context = service.switchTenant(100L, session);

        assertEquals(100L, session.get(AuthTenantService.ACTIVE_TENANT_ID));
        assertEquals("acme", session.get(AuthTenantService.ACTIVE_TENANT_CODE));
        assertEquals("Acme", session.get(AuthTenantService.ACTIVE_TENANT_NAME));
        assertEquals(new AuthTenantContext(0L, 100L, "acme", "Acme", true), context);
    }

    @Test
    void controllerContextPayloadIncludesActiveTenantCodeForLoginMeAndSwitchResponses() throws Exception {
        AuthTenantContext context = new AuthTenantContext(100L, 100L, "acme", "Acme", false);
        Method putContext = AuthController.class.getDeclaredMethod(
                "putContext", Map.class, AuthTenantContext.class);
        putContext.setAccessible(true);

        for (String response : new String[]{"login", "me", "switch"}) {
            Map<String, Object> payload = new HashMap<>();
            putContext.invoke(null, payload, context);
            assertEquals("acme", payload.get("activeTenantCode"), response);
        }
    }

    private static AuthTenantService newService(TenantService tenantService) {
        return new AuthTenantService(
                mock(SysUserMapper.class),
                mock(UserService.class),
                tenantService);
    }

    private static SysUser user(Long tenantId) {
        SysUser user = new SysUser();
        user.setTenantId(tenantId);
        return user;
    }

    private static SysTenant tenant(Long id, String code, String name) {
        SysTenant tenant = new SysTenant();
        tenant.setId(id);
        tenant.setTenantCode(code);
        tenant.setTenantName(name);
        return tenant;
    }
}
