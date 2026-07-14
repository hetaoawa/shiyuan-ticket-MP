package top.hetao.shiyuanticketmp.tenant.integration;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.Map;

@Service
public class LegacyIntegrationImporter {
    private static final long LEGACY_TENANT_ID = 100L;
    private static final String MARKER = "legacy-spring-properties-to-tenant-100-v1";

    private final Environment env;
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    private final TenantIntegrationService service;
    private final TenantIntegrationResolver resolver;

    public LegacyIntegrationImporter(Environment env, JdbcTemplate jdbc, ObjectMapper mapper,
                                     TenantIntegrationService service, TenantIntegrationResolver resolver) {
        this.env = env;
        this.jdbc = jdbc;
        this.mapper = mapper;
        this.service = service;
        this.resolver = resolver;
    }

    /**
     * Imports legacy Spring properties into tenant 100 once.
     *
     * <p>An empty import is deliberately not marked complete. Any conversion or persistence
     * failure propagates and rolls the transaction back, leaving the operation retryable.</p>
     */
    @Transactional
    public Map<String, Object> importOnce() {
        Integer done = jdbc.queryForObject(
                "SELECT COUNT(*) FROM sys_tenant_integration_migration WHERE migration_key=?",
                Integer.class, MARKER);
        if (done != null && done > 0) {
            return Map.of("imported", false, "reason", "already completed");
        }

        int changed = 0;
        changed += importType(IntegrationType.DINGTALK,
                map("workOrderDetailBaseUrl", "webhook.dingtalk.work-order-detail-base-url"),
                map("accessToken", "webhook.dingtalk.access-token", "secret", "webhook.dingtalk.secret"));
        changed += importType(IntegrationType.CARGO_OWNER,
                map("url", "webhook.cargo-owner.url",
                        "workOrderDetailBaseUrl", "webhook.cargo-owner.work-order-detail-base-url",
                        "receiveAppId", "webhook.cargo-owner.receive.app-id"),
                map("authorization", "webhook.cargo-owner.authorization",
                        "receivePrivateKey", "webhook.cargo-owner.receive.private-key"));
        changed += importType(IntegrationType.S3,
                map("endpoint", "s3.endpoint", "region", "s3.region", "bucket", "s3.bucket",
                        "pathStyle", "s3.path-style", "presignExpireSeconds", "s3.presign-expire-seconds"),
                map("accessKey", "s3.access-key", "secretKey", "s3.secret-key"));
        changed += importType(IntegrationType.EXPRESS,
                map("apiUrl", "express.api-url", "timeoutSeconds", "express.timeout-seconds"),
                map("appcode", "express.appcode"));
        changed += importType(IntegrationType.AI,
                map("apiUrl", "ai.parse.api-url", "model", "ai.parse.model",
                        "timeoutSeconds", "ai.parse.timeout-seconds"),
                map("apiKey", "ai.parse.api-key"));

        if (changed == 0) {
            return Map.of("imported", false, "reason", "no legacy values found");
        }

        jdbc.update("INSERT INTO sys_tenant_integration_migration(migration_key,detail) VALUES (?,?)",
                MARKER, "tenant=100 changed=" + changed);
        return Map.of("imported", true, "tenantId", LEGACY_TENANT_ID, "updatedTypes", changed);
    }

    private int importType(IntegrationType type, Map<String, String> publicProperties,
                           Map<String, String> secretProperties) {
        ResolvedIntegration current = resolver.resolve(LEGACY_TENANT_ID, type);
        ObjectNode config = mapper.createObjectNode();
        ObjectNode secrets = mapper.createObjectNode();

        publicProperties.forEach((field, property) -> {
            String value = env.getProperty(property);
            if (hasText(value) && !current.publicConfig().containsKey(field)) {
                if (field.endsWith("Seconds")) {
                    try {
                        config.put(field, Integer.parseInt(value));
                    } catch (NumberFormatException ignored) {
                        config.put(field, value);
                    }
                } else if ("pathStyle".equals(field)) {
                    config.put(field, Boolean.parseBoolean(value));
                } else {
                    config.put(field, value);
                }
            }
        });
        secretProperties.forEach((field, property) -> {
            String value = env.getProperty(property);
            if (hasText(value) && !current.secrets().containsKey(field)) {
                secrets.putObject(field).put("value", value);
            }
        });

        if (config.isEmpty() && secrets.isEmpty()) {
            return 0;
        }

        ObjectNode request = mapper.createObjectNode();
        request.put("configVersion", current.configVersion());
        boolean complete = isComplete(type, current, config, secrets);
        request.put("enabled", complete && (current.configVersion() == 0 || current.enabled()));
        request.set("config", config);
        request.set("secrets", secrets);
        service.update(LEGACY_TENANT_ID, type, request);
        return 1;
    }

    private boolean isComplete(IntegrationType type, ResolvedIntegration current,
                               ObjectNode config, ObjectNode secrets) {
        return switch (type) {
            case DINGTALK -> secretPresent(current, secrets, "accessToken")
                    && secretPresent(current, secrets, "secret");
            case CARGO_OWNER -> (publicPresent(current, config, "url")
                    && secretPresent(current, secrets, "authorization"))
                    || secretPresent(current, secrets, "receivePrivateKey");
            case S3 -> publicPresent(current, config, "endpoint")
                    && publicPresent(current, config, "bucket")
                    && secretPresent(current, secrets, "accessKey")
                    && secretPresent(current, secrets, "secretKey");
            case EXPRESS -> secretPresent(current, secrets, "appcode");
            case AI -> publicPresent(current, config, "apiUrl")
                    && publicPresent(current, config, "model");
        };
    }

    private boolean publicPresent(ResolvedIntegration current, ObjectNode imported, String field) {
        Object value = current.publicConfig().get(field);
        return (value != null && hasText(String.valueOf(value)))
                || (imported.has(field) && !imported.path(field).asText("").isBlank());
    }

    private boolean secretPresent(ResolvedIntegration current, ObjectNode imported, String field) {
        return hasText(current.secrets().get(field))
                || (imported.path(field).has("value")
                && !imported.path(field).path("value").asText("").isBlank());
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private Map<String, String> map(String... values) {
        Map<String, String> result = new LinkedHashMap<>();
        for (int i = 0; i < values.length; i += 2) {
            result.put(values[i], values[i + 1]);
        }
        return result;
    }
}
