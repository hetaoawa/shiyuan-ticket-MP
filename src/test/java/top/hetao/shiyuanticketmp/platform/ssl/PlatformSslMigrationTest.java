package top.hetao.shiyuanticketmp.platform.ssl;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

class PlatformSslMigrationTest {

    @Test
    void migrationCreatesGlobalTablesAndGlobalAdminPermission() throws Exception {
        String sql;
        try (var input = getClass().getResourceAsStream("/db/migration/V26__create_platform_ssl.sql")) {
            assertThat(input).isNotNull();
            sql = new String(input.readAllBytes(), StandardCharsets.UTF_8).toLowerCase();
        }

        assertThat(sql).contains("platform_ssl_config", "platform_ssl_certificate_version",
                "platform_ssl_deploy_token", "platform_ssl_operation", "platform:ssl:manage",
                "global_system_admin");
        assertThat(sql).doesNotContain("tenant_id` bigint");
    }
}
