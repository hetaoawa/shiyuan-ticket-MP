package top.hetao.shiyuanticketmp.tenant.mapper;

import com.baomidou.mybatisplus.annotation.InterceptorIgnore;
import com.baomidou.mybatisplus.core.plugins.InterceptorIgnoreHelper;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.Objects;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
        String mappedStatementId = SysTenantMapper.class.getName() + "." + mapperMethodName;

        InterceptorIgnoreHelper.initSqlParserInfoCache(null, SysTenantMapper.class.getName(), mapperMethod);

        assertTrue(InterceptorIgnoreHelper.willIgnoreTenantLine(mappedStatementId),
                mapperMethodName + " must bypass the tenant line interceptor");
        assertFalse(InterceptorIgnoreHelper.willIgnoreDataPermission(mappedStatementId),
                mapperMethodName + " must preserve the data permission interceptor");
        assertFalse(InterceptorIgnoreHelper.willIgnoreDynamicTableName(mappedStatementId),
                mapperMethodName + " must preserve the dynamic table name interceptor");
        assertFalse(InterceptorIgnoreHelper.willIgnoreBlockAttack(mappedStatementId),
                mapperMethodName + " must preserve the block attack interceptor");
        assertFalse(InterceptorIgnoreHelper.willIgnoreIllegalSql(mappedStatementId),
                mapperMethodName + " must preserve the illegal SQL interceptor");

        assertNotNull(interceptorIgnore, mapperMethodName + " must declare @InterceptorIgnore");
        assertEquals("true", interceptorIgnore.tenantLine(),
                mapperMethodName + " must bypass the tenant line interceptor");
        assertEquals("false", interceptorIgnore.dataPermission(),
                mapperMethodName + " must preserve the data permission interceptor");

        for (Method attribute : InterceptorIgnore.class.getDeclaredMethods()) {
            if (!attribute.getName().equals("tenantLine") && !attribute.getName().equals("dataPermission")) {
                assertTrue(Objects.deepEquals(attribute.getDefaultValue(), attribute.invoke(interceptorIgnore)),
                        mapperMethodName + " must leave " + attribute.getName() + " at its default");
            }
        }
    }
}
