package top.hetao.shiyuanticketmp.tenant.integration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.env.MockEnvironment;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class LegacyIntegrationImporterTest {
    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final TenantIntegrationService service = mock(TenantIntegrationService.class);
    private final TenantIntegrationResolver resolver = mock(TenantIntegrationResolver.class);
    private final ObjectMapper mapper = new ObjectMapper();

    @BeforeEach
    void setUp() {
        when(jdbc.queryForObject(anyString(), eq(Integer.class), any())).thenReturn(0);
        for (IntegrationType type : IntegrationType.values()) {
            when(resolver.resolve(100L, type)).thenReturn(
                    new ResolvedIntegration(100L, type, false, 0L, Map.of(), Map.of()));
        }
    }

    @Test
    void emptyImportDoesNotWriteCompletionMarker() {
        LegacyIntegrationImporter importer = importer(new MockEnvironment());

        Map<String, Object> result = importer.importOnce();

        assertFalse((Boolean) result.get("imported"));
        assertEquals("no legacy values found", result.get("reason"));
        verify(jdbc, never()).update(
                eq("INSERT INTO sys_tenant_integration_migration(migration_key,detail) VALUES (?,?)"),
                eq("legacy-spring-properties-to-tenant-100-v1"), eq("tenant=100 changed=1"));
        verify(service, never()).update(anyLong(), any(), any());
    }

    @Test
    void partialLegacyTypeIsImportedDisabled() {
        MockEnvironment env = new MockEnvironment()
                .withProperty("webhook.dingtalk.access-token", "legacy-token");
        LegacyIntegrationImporter importer = importer(env);

        importer.importOnce();

        ArgumentCaptor<JsonNode> request = ArgumentCaptor.forClass(JsonNode.class);
        verify(service).update(eq(100L), eq(IntegrationType.DINGTALK), request.capture());
        assertFalse(request.getValue().path("enabled").asBoolean());
        assertEquals("legacy-token",
                request.getValue().path("secrets").path("accessToken").path("value").asText());
        verify(jdbc).update(
                eq("INSERT INTO sys_tenant_integration_migration(migration_key,detail) VALUES (?,?)"),
                eq("legacy-spring-properties-to-tenant-100-v1"), eq("tenant=100 changed=1"));
    }

    @Test
    void failedImportDoesNotWriteCompletionMarkerAndRemainsRetryable() {
        MockEnvironment env = new MockEnvironment()
                .withProperty("webhook.dingtalk.access-token", "legacy-token")
                .withProperty("webhook.dingtalk.secret", "legacy-secret");
        when(service.update(eq(100L), eq(IntegrationType.DINGTALK), any()))
                .thenThrow(new IllegalArgumentException("write failed"));
        LegacyIntegrationImporter importer = importer(env);

        assertThrows(IllegalArgumentException.class, importer::importOnce);

        verify(jdbc, never()).update(
                eq("INSERT INTO sys_tenant_integration_migration(migration_key,detail) VALUES (?,?)"),
                eq("legacy-spring-properties-to-tenant-100-v1"), eq("tenant=100 changed=1"));
    }

    private LegacyIntegrationImporter importer(MockEnvironment environment) {
        return new LegacyIntegrationImporter(environment, jdbc, mapper, service, resolver);
    }
}
