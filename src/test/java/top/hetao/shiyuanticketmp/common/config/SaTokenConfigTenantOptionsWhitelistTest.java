package top.hetao.shiyuanticketmp.common.config;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class SaTokenConfigTenantOptionsWhitelistTest {

    private static final String TENANT_OPTIONS_PATH = "/api/auth/tenant-options";

    @Test
    void tenantOptionsIsAnExactAuthenticationExclusion() throws ReflectiveOperationException {
        assertExactExclusion("AUTH_EXCLUDE_PATHS");
    }

    @Test
    void tenantOptionsIsAnExactTenantContextExclusion() throws ReflectiveOperationException {
        assertExactExclusion("TENANT_EXCLUDE_PATHS");
    }

    private static void assertExactExclusion(String fieldName) throws ReflectiveOperationException {
        List<String> exclusions = readExclusions(fieldName);
        assertEquals(1, exclusions.stream().filter(TENANT_OPTIONS_PATH::equals).count());
        assertFalse(exclusions.contains("/api/auth/**"));
    }

    private static List<String> readExclusions(String fieldName) throws ReflectiveOperationException {
        Field field = SaTokenConfig.class.getDeclaredField(fieldName);
        field.setAccessible(true);
        return Arrays.asList((String[]) field.get(null));
    }
}
