package top.hetao.shiyuanticketmp.tenant.integration;

import org.junit.jupiter.api.Test;
import java.nio.file.Files;
import java.nio.file.Path;
import static org.junit.jupiter.api.Assertions.*;

class TenantIntegrationMigrationTest {
    @Test void v25CreatesUniqueTenantTypeAndFieldEncryptedStorage() throws Exception {
        String sql=Files.readString(Path.of("src/main/resources/db/migration/V25__create_tenant_integration.sql"));
        assertTrue(sql.contains("uk_tenant_integration_type"));
        assertTrue(sql.contains("`secret_config_json`"));
        assertTrue(sql.contains("sys_tenant_integration_migration"));
        assertTrue(sql.contains("externalInboundEnabled"));
    }
}
