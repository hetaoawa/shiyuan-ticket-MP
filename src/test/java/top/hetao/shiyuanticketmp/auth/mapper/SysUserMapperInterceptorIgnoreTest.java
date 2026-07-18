package top.hetao.shiyuanticketmp.auth.mapper;

import com.baomidou.mybatisplus.annotation.InterceptorIgnore;
import com.baomidou.mybatisplus.core.plugins.InterceptorIgnoreHelper;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.Objects;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SysUserMapperInterceptorIgnoreTest {

    @Test
    void selectByIdIgnoreTenantBypassesOnlyTenantLineInterceptor() throws ReflectiveOperationException {
        Method mapperMethod = SysUserMapper.class.getDeclaredMethod("selectByIdIgnoreTenant", Long.class);
        InterceptorIgnore interceptorIgnore = mapperMethod.getAnnotation(InterceptorIgnore.class);
        String mappedStatementId = SysUserMapper.class.getName() + ".selectByIdIgnoreTenant";

        InterceptorIgnoreHelper.initSqlParserInfoCache(null, SysUserMapper.class.getName(), mapperMethod);

        assertTrue(InterceptorIgnoreHelper.willIgnoreTenantLine(mappedStatementId));
        assertFalse(InterceptorIgnoreHelper.willIgnoreDataPermission(mappedStatementId));
        assertFalse(InterceptorIgnoreHelper.willIgnoreDynamicTableName(mappedStatementId));
        assertFalse(InterceptorIgnoreHelper.willIgnoreBlockAttack(mappedStatementId));
        assertFalse(InterceptorIgnoreHelper.willIgnoreIllegalSql(mappedStatementId));

        assertNotNull(interceptorIgnore);
        assertEquals("true", interceptorIgnore.tenantLine());
        assertEquals("false", interceptorIgnore.dataPermission());
        for (Method attribute : InterceptorIgnore.class.getDeclaredMethods()) {
            if (!attribute.getName().equals("tenantLine") && !attribute.getName().equals("dataPermission")) {
                assertTrue(Objects.deepEquals(attribute.getDefaultValue(), attribute.invoke(interceptorIgnore)),
                        attribute.getName() + " must keep its default value");
            }
        }
    }
}
