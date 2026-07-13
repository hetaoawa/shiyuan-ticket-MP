package top.hetao.shiyuanticketmp.auth;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import top.hetao.shiyuanticketmp.auth.controller.dto.TenantOptionResponse;
import top.hetao.shiyuanticketmp.tenant.service.TenantService;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AuthTenantOptionControllerTest {

    @Mock
    private TenantService tenantService;

    @Test
    void tenantOptionsReturnsProjectResponseEnvelope() {
        List<TenantOptionResponse> options = List.of(
                new TenantOptionResponse("platform", "平台"),
                new TenantOptionResponse("warehouse", "仓库")
        );
        when(tenantService.listLoginOptions()).thenReturn(options);
        AuthTenantOptionController controller = new AuthTenantOptionController(tenantService);

        Map<String, Object> response = controller.tenantOptions();

        assertEquals(Set.of("code", "message", "data"), response.keySet());
        assertEquals(200, response.get("code"));
        assertEquals("获取租户选项成功", response.get("message"));
        assertEquals(options, response.get("data"));
        verify(tenantService).listLoginOptions();
    }

    @Test
    void tenantOptionsUsesExactGetMappingAndConstructorInjection() throws ReflectiveOperationException {
        RequestMapping classMapping = AuthTenantOptionController.class.getAnnotation(RequestMapping.class);
        Method endpoint = AuthTenantOptionController.class.getDeclaredMethod("tenantOptions");
        GetMapping methodMapping = endpoint.getAnnotation(GetMapping.class);

        assertArrayEquals(new String[]{"/api/auth"}, classMapping.value());
        assertArrayEquals(new String[]{"/tenant-options"}, methodMapping.value());
        Constructor<?>[] constructors = AuthTenantOptionController.class.getDeclaredConstructors();
        assertEquals(1, constructors.length);
        assertArrayEquals(new Class<?>[]{TenantService.class}, constructors[0].getParameterTypes());
    }
}
