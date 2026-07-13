package top.hetao.shiyuanticketmp.tenant.mapper;

import com.baomidou.mybatisplus.annotation.InterceptorIgnore;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.Objects;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SysTenantMapperInterceptorIgnoreTest {

    @Test
    void selectEnabledByIdForShareBypassesOnlyTenantLineInterceptor() throws ReflectiveOperationException {
        assertBypassesOnlyTenantLineInterceptor("selectEnabledByIdForShare");
    }

    @Test
    void selectNotDeletedByIdForShareBypassesOnlyTenantLineInterceptor() throws ReflectiveOperationException {
        assertBypassesOnlyTenantLineInterceptor("selectNotDeletedByIdForShare");
    }

    private void assertBypassesOnlyTenantLineInterceptor(String mapperMethodName) throws ReflectiveOperationException {
        Method mapperMethod = SysTenantMapper.class.getDeclaredMethod(mapperMethodName, Long.class);
        InterceptorIgnore interceptorIgnore = mapperMethod.getAnnotation(InterceptorIgnore.class);

        assertNotNull(interceptorIgnore, mapperMethodName + " must declare @InterceptorIgnore");
        assertEquals("true", interceptorIgnore.tenantLine(),
                mapperMethodName + " must bypass the tenant line interceptor");

        for (Method attribute : InterceptorIgnore.class.getDeclaredMethods()) {
            if (!attribute.getName().equals("tenantLine")) {
                assertTrue(Objects.deepEquals(attribute.getDefaultValue(), attribute.invoke(interceptorIgnore)),
                        mapperMethodName + " must leave " + attribute.getName() + " at its default");
            }
        }
    }
}
