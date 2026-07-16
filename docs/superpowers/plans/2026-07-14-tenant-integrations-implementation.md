# Tenant Integrations Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace shared Spring business credentials with tenant-scoped, typed, encrypted, hot-reloadable DingTalk, cargo-owner Webhook, S3, express, and AI configuration, including a safe one-time import of the existing production values into tenant `100` and a permission-aware settings UI.

**Architecture:** Add a tenant-filtered `sys_tenant_integration` row per `(tenant_id, integration_type)`, store public typed JSON separately from per-field AES-256-GCM ciphertext maps, and update rows with optimistic `config_version` compare-and-set. A resolver accepts an explicit tenant ID, uses a short local cache plus Redis invalidation, and is the only runtime source for the five integrations; S3 resources are additionally keyed by `(tenantId, configVersion)`. The old V19 switch table remains read-only input to the explicit importer during rollout, while normal runtime defaults missing rows to disabled and never falls back outside tenant `100`.

**Tech Stack:** Java 17, Spring Boot 3.0.2, MyBatis-Plus 3.5.5, MySQL 8 JSON, AES/GCM/NoPadding, Spring Data Redis pub/sub, AWS SDK v2 S3, JUnit 5/Mockito, Vue 3, Element Plus, Node test runner.

---

## Scope and file map

This plan does not implement platform SSL, batch work orders, batch AI, login/navigation, or opaque-ID work. “QQ configuration” means the existing `CARGO_OWNER` custom Webhook only: outbound URL/authorization, inbound appId/private key, detail URL, the current RSA/MD5 verification algorithm, current message shape, aggregation, retries, and dead letters. Do not add a WebSocket dependency, address, protocol, controller, or UI field.

Create these focused backend files:

- `src/main/resources/db/migration/V25__create_tenant_integrations.sql`: new tenant rows, import marker, and idempotent permissions.
- `src/main/java/top/hetao/shiyuanticketmp/tenant/integration/IntegrationType.java`: closed type enum.
- `src/main/java/top/hetao/shiyuanticketmp/tenant/integration/entity/SysTenantIntegration.java`: non-logically-deleted row model.
- `src/main/java/top/hetao/shiyuanticketmp/tenant/integration/mapper/SysTenantIntegrationMapper.java`: tenant reads, CAS writes, and appId lookup.
- `src/main/java/top/hetao/shiyuanticketmp/tenant/integration/model/IntegrationModels.java`: typed runtime configs, patches, and sanitized views.
- `src/main/java/top/hetao/shiyuanticketmp/tenant/integration/crypto/IntegrationCryptoProperties.java`: environment-backed key ring.
- `src/main/java/top/hetao/shiyuanticketmp/tenant/integration/crypto/IntegrationSecretCrypto.java`: field-bound GCM encryption.
- `src/main/java/top/hetao/shiyuanticketmp/tenant/integration/security/ExternalUrlValidator.java`: URL/SSRF policy.
- `src/main/java/top/hetao/shiyuanticketmp/tenant/integration/service/TenantIntegrationService.java`: typed patch, validation, versioning, and audit.
- `src/main/java/top/hetao/shiyuanticketmp/tenant/integration/service/TenantIntegrationResolver.java`: explicit-tenant runtime resolution and TTL cache.
- `src/main/java/top/hetao/shiyuanticketmp/tenant/integration/event/IntegrationConfigChanged.java`: versioned invalidation event.
- `src/main/java/top/hetao/shiyuanticketmp/tenant/integration/config/IntegrationInvalidationConfig.java`: local after-commit and Redis invalidation.
- `src/main/java/top/hetao/shiyuanticketmp/tenant/integration/controller/TenantIntegrationController.java`: five typed PUT endpoints and sanitized GET.
- `src/main/java/top/hetao/shiyuanticketmp/tenant/integration/migration/LegacyTenant100IntegrationImporter.java`: explicit one-shot importer.
- `src/main/java/top/hetao/shiyuanticketmp/file/service/TenantS3ResourceFactory.java`: version-keyed, closeable S3 resource cache.

The four approved plans reserve migration versions centrally: batch work orders use V24, tenant integrations use V25, and platform SSL uses V26. Before Task 1, run `Get-ChildItem src/main/resources/db/migration/V* | Sort-Object Name` and stop if V25 is already occupied; do not silently create a duplicate Flyway version.

Modify the existing runtime files named in Tasks 6–9. Delete the old V19 runtime controller/service/DTO after callers move, but retain `SysTenantSetting` and `SysTenantSettingMapper` only as importer input until a later production cleanup migration.

Create/modify these frontend files (paths are relative to `backend/` as required):

- Create `../front/src/views/settings/integration-form.js`.
- Create `../front/tests/integration-settings.test.mjs`.
- Modify `../front/src/api/admin/settings.js`.
- Replace the external-integration section in `../front/src/views/settings/index.vue`.

API contract introduced by this plan:

```text
GET /api/admin/settings/integrations
PUT /api/admin/settings/integrations/dingtalk
PUT /api/admin/settings/integrations/cargo-owner
PUT /api/admin/settings/integrations/s3
PUT /api/admin/settings/integrations/express
PUT /api/admin/settings/integrations/ai
```

Every PUT requires `expectedConfigVersion`. A missing row is version `0`; the first successful insert returns version `1`. A version mismatch is HTTP 409. Secret properties are `SecretPatch` objects: absent/null means keep, `{ "clear": true }` means clear, and `{ "value": "new secret" }` means replace. Responses contain only public config plus `secretConfigured` booleans.

### Task 1: Create the V25 schema and closed integration domain

**Files:**
- Create: `src/main/resources/db/migration/V25__create_tenant_integrations.sql`
- Create: `src/main/java/top/hetao/shiyuanticketmp/tenant/integration/IntegrationType.java`
- Create: `src/main/java/top/hetao/shiyuanticketmp/tenant/integration/entity/SysTenantIntegration.java`
- Create: `src/main/java/top/hetao/shiyuanticketmp/tenant/integration/mapper/SysTenantIntegrationMapper.java`
- Modify: `src/main/java/top/hetao/shiyuanticketmp/common/config/MybatisPlusConfig.java`
- Modify: `src/main/java/top/hetao/shiyuanticketmp/tenant/mapper/SysTenantMapper.java`
- Test: `src/test/java/top/hetao/shiyuanticketmp/tenant/integration/IntegrationSchemaContractTest.java`

- [ ] **Step 1: Write the failing schema contract test**

```java
package top.hetao.shiyuanticketmp.tenant.integration;

import org.junit.jupiter.api.Test;
import java.nio.file.Files;
import java.nio.file.Path;
import static org.junit.jupiter.api.Assertions.*;

class IntegrationSchemaContractTest {
    @Test
    void v25_has_tenant_type_uniqueness_versions_and_no_logical_delete() throws Exception {
        String sql = Files.readString(Path.of("src/main/resources/db/migration/V25__create_tenant_integrations.sql"));
        assertTrue(sql.contains("CREATE TABLE `sys_tenant_integration`"));
        assertTrue(sql.contains("UNIQUE KEY `uk_tenant_integration_type` (`tenant_id`, `integration_type`)"));
        assertTrue(sql.contains("`config_version` BIGINT NOT NULL DEFAULT 1"));
        assertTrue(sql.contains("`secret_ciphertext` JSON NULL"));
        assertTrue(sql.contains("`secret_nonce` JSON NULL"));
        assertFalse(sql.contains("`deleted`"));
        assertTrue(sql.contains("sys_tenant_integration_import_marker"));
    }
}
```

- [ ] **Step 2: Run it and verify the intended failure**

Run: `mvn -Dtest=IntegrationSchemaContractTest test`

Expected: FAIL because `V25__create_tenant_integrations.sql` does not exist.

- [ ] **Step 3: Add the migration exactly once**

```sql
CREATE TABLE `sys_tenant_integration` (
    `id` BIGINT NOT NULL,
    `tenant_id` BIGINT NOT NULL,
    `integration_type` VARCHAR(32) NOT NULL,
    `enabled` TINYINT(1) NOT NULL DEFAULT 0,
    `config_json` JSON NOT NULL,
    `secret_ciphertext` JSON NULL,
    `secret_nonce` JSON NULL,
    `key_version` INT NULL,
    `config_version` BIGINT NOT NULL DEFAULT 1,
    `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_tenant_integration_type` (`tenant_id`, `integration_type`),
    KEY `idx_tenant_integration_tenant_version` (`tenant_id`, `config_version`),
    CONSTRAINT `chk_tenant_integration_type`
      CHECK (`integration_type` IN ('DINGTALK','CARGO_OWNER','S3','EXPRESS','AI'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Typed tenant external integrations';

CREATE TABLE `sys_tenant_integration_import_marker` (
    `marker_key` VARCHAR(64) NOT NULL,
    `tenant_id` BIGINT NOT NULL,
    `fingerprint_sha256` CHAR(64) NOT NULL,
    `completed_at` DATETIME NOT NULL,
    PRIMARY KEY (`marker_key`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Non-secret one-shot import markers';

INSERT IGNORE INTO `sys_permission` (`id`, `permission_code`, `permission_name`) VALUES
    (20260713000000001, 'settings:view', '查看租户外部集成设置'),
    (20260713000000002, 'settings:update', '更新租户外部集成设置');
```

- [ ] **Step 4: Add the enum, row, and mapper**

```java
package top.hetao.shiyuanticketmp.tenant.integration;

public enum IntegrationType { DINGTALK, CARGO_OWNER, S3, EXPRESS, AI }
```

```java
package top.hetao.shiyuanticketmp.tenant.integration.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;
import top.hetao.shiyuanticketmp.tenant.integration.IntegrationType;
import java.time.LocalDateTime;

@Data
@TableName("sys_tenant_integration")
public class SysTenantIntegration {
    @TableId(type = IdType.ASSIGN_ID) private Long id;
    private Long tenantId;
    private IntegrationType integrationType;
    private Boolean enabled;
    private String configJson;
    private String secretCiphertext;
    private String secretNonce;
    private Integer keyVersion;
    private Long configVersion;
    @TableField(fill = FieldFill.INSERT) private LocalDateTime createdAt;
    @TableField(fill = FieldFill.INSERT_UPDATE) private LocalDateTime updatedAt;
}
```

```java
package top.hetao.shiyuanticketmp.tenant.integration.mapper;

import com.baomidou.mybatisplus.annotation.InterceptorIgnore;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.*;
import top.hetao.shiyuanticketmp.tenant.integration.IntegrationType;
import top.hetao.shiyuanticketmp.tenant.integration.entity.SysTenantIntegration;
import java.util.List;

@Mapper
public interface SysTenantIntegrationMapper extends BaseMapper<SysTenantIntegration> {
    @Update("""
        UPDATE sys_tenant_integration SET enabled=#{row.enabled}, config_json=#{row.configJson},
          secret_ciphertext=#{row.secretCiphertext}, secret_nonce=#{row.secretNonce},
          key_version=#{row.keyVersion}, config_version=config_version+1, updated_at=CURRENT_TIMESTAMP
        WHERE tenant_id=#{row.tenantId} AND integration_type=#{row.integrationType}
          AND config_version=#{expectedVersion}
        """)
    int updateIfVersion(@Param("row") SysTenantIntegration row,
                        @Param("expectedVersion") long expectedVersion);

    @Select("""
        SELECT i.tenant_id FROM sys_tenant_integration i
        JOIN sys_tenant t ON t.id=i.tenant_id AND t.status=1 AND t.deleted=0
        WHERE i.integration_type='CARGO_OWNER'
          AND JSON_UNQUOTE(JSON_EXTRACT(i.config_json, '$.receiveAppId'))=#{appId}
        """)
    @InterceptorIgnore(tenantLine = "true", dataPermission = "false")
    List<Long> selectTenantIdsByCargoOwnerAppId(@Param("appId") String appId);
}
```

Add `sys_tenant_integration_import_marker` to `GLOBAL_TABLES`; do not add `sys_tenant_integration`. Add this method to `SysTenantMapper` and invoke it during tenant deletion beside `deleteTenantSettings`:

```java
@Delete("DELETE FROM sys_tenant_integration WHERE tenant_id = #{tenantId}")
int deleteTenantIntegrations(@Param("tenantId") Long tenantId);
```

- [ ] **Step 5: Verify and commit**

Run: `mvn -Dtest=IntegrationSchemaContractTest test`

Expected: PASS.

```bash
git add src/main/resources/db/migration/V25__create_tenant_integrations.sql src/main/java/top/hetao/shiyuanticketmp/tenant/integration src/main/java/top/hetao/shiyuanticketmp/common/config/MybatisPlusConfig.java src/main/java/top/hetao/shiyuanticketmp/tenant/mapper/SysTenantMapper.java src/test/java/top/hetao/shiyuanticketmp/tenant/integration/IntegrationSchemaContractTest.java
git commit -m "feat: add typed tenant integration schema"
```

### Task 2: Implement field-bound AES-256-GCM and URL safety

**Files:**
- Create: `src/main/java/top/hetao/shiyuanticketmp/tenant/integration/crypto/IntegrationCryptoProperties.java`
- Create: `src/main/java/top/hetao/shiyuanticketmp/tenant/integration/crypto/IntegrationSecretCrypto.java`
- Create: `src/main/java/top/hetao/shiyuanticketmp/tenant/integration/security/ExternalUrlValidator.java`
- Modify: `src/main/resources/application.properties`
- Test: `src/test/java/top/hetao/shiyuanticketmp/tenant/integration/crypto/IntegrationSecretCryptoTest.java`
- Test: `src/test/java/top/hetao/shiyuanticketmp/tenant/integration/security/ExternalUrlValidatorTest.java`

- [ ] **Step 1: Write failing crypto tests**

```java
class IntegrationSecretCryptoTest {
    private final IntegrationSecretCrypto crypto = IntegrationSecretCrypto.forTest(
            7, java.util.Map.of(7, new byte[32]));

    @Test void roundTrips_each_field() {
        var encrypted = crypto.encrypt(100L, IntegrationType.CARGO_OWNER,
                java.util.Map.of("authorization", "Bearer x", "receivePrivateKey", "pem"));
        assertEquals("Bearer x", crypto.decrypt(100L, IntegrationType.CARGO_OWNER, encrypted)
                .get("authorization"));
    }

    @Test void wrong_tenant_type_or_field_and_tampering_fail_closed() {
        var encrypted = crypto.encrypt(100L, IntegrationType.AI, java.util.Map.of("apiKey", "secret"));
        assertThrows(IllegalStateException.class,
                () -> crypto.decrypt(101L, IntegrationType.AI, encrypted));
        String damaged = encrypted.ciphertextJson().replaceFirst("A", "B");
        assertThrows(IllegalStateException.class,
                () -> crypto.decrypt(100L, IntegrationType.AI,
                        new IntegrationSecretCrypto.EncryptedSecrets(
                                damaged, encrypted.nonceJson(), encrypted.keyVersion())));
    }
}
```

- [ ] **Step 2: Run the tests and verify missing-class failures**

Run: `mvn -Dtest=IntegrationSecretCryptoTest,ExternalUrlValidatorTest test`

Expected: FAIL because the crypto and validator classes do not exist.

- [ ] **Step 3: Implement the key ring and GCM envelope**

Use a 12-byte random nonce per secret field, a 128-bit GCM tag, and exact AAD bytes from `tenantId + ":" + integrationType + ":" + fieldName`. Store ciphertext and nonce as JSON objects keyed by the stable secret field name. On every secret update, decrypt the whole bundle, merge it, and re-encrypt every remaining field using the active key version so the single `key_version` remains truthful.

```java
@Component
@ConfigurationProperties(prefix = "tenant-integration.crypto")
public class IntegrationCryptoProperties {
    private int activeKeyVersion;
    private Map<Integer, String> keys = new HashMap<>();
    public int getActiveKeyVersion() { return activeKeyVersion; }
    public void setActiveKeyVersion(int value) { activeKeyVersion = value; }
    public Map<Integer, String> getKeys() { return keys; }
    public void setKeys(Map<Integer, String> value) { keys = value; }
    public SecretKey key(int version) {
        String encoded = keys.get(version);
        if (encoded == null) throw new IllegalStateException("Missing integration key version " + version);
        byte[] bytes = Base64.getDecoder().decode(encoded);
        if (bytes.length != 32) throw new IllegalStateException("Integration key must be 32 bytes");
        return new SecretKeySpec(bytes, "AES");
    }
}
```

`IntegrationSecretCrypto` must expose only these methods; catch `GeneralSecurityException` and throw `IllegalStateException("Tenant integration secret authentication failed", cause)` without values:

```java
public record EncryptedSecrets(String ciphertextJson, String nonceJson, int keyVersion) {}
public EncryptedSecrets encrypt(long tenantId, IntegrationType type, Map<String,String> plaintext);
public Map<String,String> decrypt(long tenantId, IntegrationType type, EncryptedSecrets encrypted);
```

Add environment-only bindings; never put real key material in YAML or commits:

```properties
tenant-integration.crypto.active-key-version=${TENANT_INTEGRATION_ACTIVE_KEY_VERSION}
tenant-integration.crypto.keys.1=${TENANT_INTEGRATION_MASTER_KEY_V1}
tenant-integration.cache-ttl-seconds=30
tenant-integration.invalidation-channel=tenant-integration:invalidate
tenant-integration.allowed-private-hosts=${TENANT_INTEGRATION_ALLOWED_PRIVATE_HOSTS:}
tenant-integration.legacy-fallback-tenant100-enabled=${TENANT_INTEGRATION_LEGACY_FALLBACK_TENANT100_ENABLED:false}
tenant-integration.legacy-import-enabled=${TENANT_INTEGRATION_LEGACY_IMPORT_ENABLED:false}
```

- [ ] **Step 4: Implement and test URL policy**

`ExternalUrlValidator.validateHttpUrl(label, value, required, webhookTarget)` must reject values over 2048 characters, non-HTTP(S), user-info, missing host, `.localhost`, any resolved any-local/loopback/link-local/site-local/multicast address, and IPv6 `fc00::/7`. Only exact normalized host names listed in `tenant-integration.allowed-private-hosts` bypass the private-address checks. Call it again immediately before Webhook delivery, not only on save.

```java
@Test void webhook_rejects_loopback_private_and_link_local() {
    assertThrows(IllegalArgumentException.class,
            () -> validator.validateHttpUrl("targetUrl", "http://127.0.0.1/hook", true, true));
    assertThrows(IllegalArgumentException.class,
            () -> validator.validateHttpUrl("targetUrl", "http://169.254.1.1/hook", true, true));
    assertThrows(IllegalArgumentException.class,
            () -> validator.validateHttpUrl("targetUrl", "http://10.0.0.2/hook", true, true));
}
```

- [ ] **Step 5: Verify and commit**

Run: `mvn -Dtest=IntegrationSecretCryptoTest,ExternalUrlValidatorTest test`

Expected: PASS, including GCM tamper and AAD isolation cases.

```bash
git add src/main/java/top/hetao/shiyuanticketmp/tenant/integration/crypto src/main/java/top/hetao/shiyuanticketmp/tenant/integration/security src/main/resources/application.properties src/test/java/top/hetao/shiyuanticketmp/tenant/integration
git commit -m "feat: encrypt tenant integration secrets"
```

### Task 3: Define typed configurations and safe secret patch semantics

**Files:**
- Create: `src/main/java/top/hetao/shiyuanticketmp/tenant/integration/model/IntegrationModels.java`
- Test: `src/test/java/top/hetao/shiyuanticketmp/tenant/integration/model/IntegrationModelsTest.java`

- [ ] **Step 1: Write the failing model tests**

```java
@Test void secret_patch_distinguishes_keep_clear_and_replace() {
    assertEquals("old", IntegrationModels.applySecret("old", null));
    assertNull(IntegrationModels.applySecret("old", new SecretPatch(null, true)));
    assertEquals("new", IntegrationModels.applySecret("old", new SecretPatch(" new ", false)));
    assertThrows(IllegalArgumentException.class,
            () -> IntegrationModels.applySecret("old", new SecretPatch("new", true)));
}

@Test void qq_is_not_an_integration_type() {
    assertThrows(IllegalArgumentException.class, () -> IntegrationType.valueOf("QQ"));
    assertEquals(5, IntegrationType.values().length);
}
```

- [ ] **Step 2: Run and verify failure**

Run: `mvn -Dtest=IntegrationModelsTest test`

Expected: FAIL because `IntegrationModels` does not exist.

- [ ] **Step 3: Add the closed request/runtime/view model**

Create one final utility class with these nested records and no Lombok-generated `toString` on secret-bearing records:

```java
public final class IntegrationModels {
    private IntegrationModels() {}
    public record SecretPatch(String value, Boolean clear) {}
    public record UpdatePatch(Long expectedConfigVersion, Boolean enabled,
                              Map<String,Object> publicConfig,
                              Map<String,SecretPatch> secrets) {}
    public record Resolved<T>(long tenantId, long configVersion, T config) {}
    public record View(IntegrationType type, boolean enabled, boolean complete,
                       long configVersion, Object config,
                       Map<String,Boolean> secretConfigured) {}

    public record DingTalk(String accessToken, String signingSecret,
                           String workOrderDetailBaseUrl) {}
    public record CargoOwner(String targetUrl, String authorization,
                             String receiveAppId, String receivePrivateKey,
                             String workOrderDetailBaseUrl,
                             boolean inboundEnabled, boolean closeCallbackEnabled) {}
    public record S3(String endpoint, String accessKey, String secretKey, String region,
                     String bucket, boolean pathStyleAccess, long presignExpireSeconds) {}
    public record Express(String apiUrl, String appCode, int timeoutSeconds) {}
    public record Ai(String apiUrl, String apiKey, String model, int timeoutSeconds) {}

    public record DingTalkUpdate(Long expectedConfigVersion, Boolean enabled,
            String workOrderDetailBaseUrl, SecretPatch accessToken, SecretPatch signingSecret) {}
    public record CargoOwnerUpdate(Long expectedConfigVersion, Boolean enabled,
            String targetUrl, String receiveAppId, String workOrderDetailBaseUrl,
            Boolean inboundEnabled, Boolean closeCallbackEnabled,
            SecretPatch authorization, SecretPatch receivePrivateKey) {}
    public record S3Update(Long expectedConfigVersion, Boolean enabled, String endpoint,
            String region, String bucket, Boolean pathStyleAccess, Long presignExpireSeconds,
            SecretPatch accessKey, SecretPatch secretKey) {}
    public record ExpressUpdate(Long expectedConfigVersion, Boolean enabled,
            String apiUrl, Integer timeoutSeconds, SecretPatch appCode) {}
    public record AiUpdate(Long expectedConfigVersion, Boolean enabled,
            String apiUrl, String model, Integer timeoutSeconds, SecretPatch apiKey) {}

    public static String applySecret(String current, SecretPatch patch) {
        if (patch == null) return current;
        boolean clear = Boolean.TRUE.equals(patch.clear());
        boolean hasValue = patch.value() != null && !patch.value().isBlank();
        if (clear == hasValue) throw new IllegalArgumentException(
                "Secret patch must contain exactly one of clear=true or a non-blank value");
        return clear ? null : patch.value().trim();
    }
}
```

Annotate all five update records with `@JsonIgnoreProperties(ignoreUnknown = false)` so arbitrary keys and any attempted WebSocket fields are rejected as HTTP 400. Completeness is strict: enabled DingTalk requires token/signing secret; cargo-owner requires URL/authorization/appId/private key; S3 requires endpoint/access key/secret key/region/bucket; express requires URL/appCode; AI requires URL/apiKey/model. Optional detail URLs may be blank. Bounds: timeout 1–120 seconds, presign expiry 60–86400 seconds.

- [ ] **Step 4: Verify and commit**

Run: `mvn -Dtest=IntegrationModelsTest test`

Expected: PASS.

```bash
git add src/main/java/top/hetao/shiyuanticketmp/tenant/integration/model src/test/java/top/hetao/shiyuanticketmp/tenant/integration/model
git commit -m "feat: define typed integration contracts"
```

### Task 4: Build transactional CRUD, optimistic versions, sanitized views, and audit

**Files:**
- Create: `src/main/java/top/hetao/shiyuanticketmp/tenant/integration/service/TenantIntegrationService.java`
- Create: `src/main/java/top/hetao/shiyuanticketmp/tenant/integration/event/IntegrationConfigChanged.java`
- Create: `src/main/java/top/hetao/shiyuanticketmp/common/exception/ConflictException.java`
- Modify: `src/main/java/top/hetao/shiyuanticketmp/common/exception/GlobalExceptionHandler.java`
- Test: `src/test/java/top/hetao/shiyuanticketmp/tenant/integration/service/TenantIntegrationServiceTest.java`

- [ ] **Step 1: Write failing service tests**

Cover all of these cases with Mockito: no row returns `enabled=false`, `complete=false`, version `0`; a missing secret patch keeps the decrypted value; clear removes it; responses contain only configured booleans; enabled incomplete config is rejected; a CAS row count of zero throws `ConflictException`; cross-tenant appId collision is rejected; audit JSON contains only `integrationType`, `configVersion`, `action`, and changed secret field names.

```java
@Test void version_conflict_is_409_domain_error() {
    when(mapper.updateIfVersion(any(), eq(3L))).thenReturn(0);
    assertThrows(ConflictException.class,
            () -> service.update(100L, 9L, IntegrationType.AI, validAiPatch(3L)));
}

@Test void view_never_contains_secret_plaintext() {
    View view = service.get(100L, IntegrationType.AI);
    assertFalse(view.toString().contains("plain-api-key"));
    assertEquals(Map.of("apiKey", true), view.secretConfigured());
}
```

- [ ] **Step 2: Run and verify failure**

Run: `mvn -Dtest=TenantIntegrationServiceTest test`

Expected: FAIL because the service and conflict exception do not exist.

- [ ] **Step 3: Implement the transaction boundary**

`TenantIntegrationService.update(long tenantId, long operatorId, IntegrationType type, UpdatePatch patch)` must:

1. reject null wrapper fields and lock `TenantLifecycleGuard.lockWritableTenant(tenantId)`;
2. scope every tenant-table query with `TenantContext.useTenant(tenantId)`;
3. decrypt the current secret map, apply only named patches, and re-encrypt all remaining fields;
4. build exactly one of the five typed runtime records and validate its fields/URLs;
5. reject `enabled=true` unless complete;
6. for cargo-owner, ensure `receiveAppId` maps to no other tenant;
7. insert with version 1 only when expected version is 0, otherwise use `updateIfVersion`;
8. insert a `SysAuditLog` with `bizType="TENANT_INTEGRATION"`, row ID, operator, tenant, action, and non-secret detail;
9. publish `new IntegrationConfigChanged(tenantId, type, newVersion)`; and
10. return a sanitized `View` assembled from public fields and secret-presence booleans.

The event is an immutable record:

```java
public record IntegrationConfigChanged(long tenantId, IntegrationType type, long configVersion) {}
```

Use this conflict type and handler:

```java
public class ConflictException extends RuntimeException {
    public ConflictException(String message) { super(message); }
}
```

```java
@ExceptionHandler(ConflictException.class)
public ResponseEntity<Map<String,Object>> handleConflict(ConflictException e) {
    return ResponseEntity.status(HttpStatus.CONFLICT)
            .body(Map.of("code", 409, "message", e.getMessage()));
}
```

Do not log request DTOs, decrypted maps, ciphertext, nonce, headers, or exception messages from cryptographic primitives. Use audit detail shaped exactly as follows:

```json
{"integrationType":"AI","configVersion":4,"action":"UPDATE","changedSecretFields":["apiKey"]}
```

- [ ] **Step 4: Run tests and commit**

Run: `mvn -Dtest=TenantIntegrationServiceTest test`

Expected: PASS.

```bash
git add src/main/java/top/hetao/shiyuanticketmp/tenant/integration/service/TenantIntegrationService.java src/main/java/top/hetao/shiyuanticketmp/tenant/integration/event src/main/java/top/hetao/shiyuanticketmp/common/exception src/test/java/top/hetao/shiyuanticketmp/tenant/integration/service
git commit -m "feat: manage versioned tenant integrations"
```

### Task 5: Add explicit-tenant resolution and multi-instance invalidation

**Files:**
- Create: `src/main/java/top/hetao/shiyuanticketmp/tenant/integration/service/TenantIntegrationResolver.java`
- Create: `src/main/java/top/hetao/shiyuanticketmp/tenant/integration/config/IntegrationInvalidationConfig.java`
- Test: `src/test/java/top/hetao/shiyuanticketmp/tenant/integration/service/TenantIntegrationResolverTest.java`

- [ ] **Step 1: Write failing cache/invalidation tests**

```java
@Test void cache_key_contains_tenant_and_type_and_expires() {
    resolver.require(100L, IntegrationType.AI, IntegrationModels.Ai.class);
    resolver.require(100L, IntegrationType.AI, IntegrationModels.Ai.class);
    verify(mapper, times(1)).selectOne(any());
    resolver.invalidate(100L, IntegrationType.AI, 2L);
    resolver.require(100L, IntegrationType.AI, IntegrationModels.Ai.class);
    verify(mapper, times(2)).selectOne(any());
}

@Test void missing_or_disabled_never_defaults_enabled() {
    when(mapper.selectOne(any())).thenReturn(null);
    assertThrows(IllegalStateException.class,
            () -> resolver.require(200L, IntegrationType.DINGTALK, IntegrationModels.DingTalk.class));
}
```

- [ ] **Step 2: Run and verify failure**

Run: `mvn -Dtest=TenantIntegrationResolverTest test`

Expected: FAIL because the resolver does not exist.

- [ ] **Step 3: Implement resolver semantics**

Expose only explicit-tenant methods; never infer an async tenant from a leftover ThreadLocal:

```java
public <T> Resolved<T> require(long tenantId, IntegrationType type, Class<T> expectedType);
public View inspect(long tenantId, IntegrationType type);
public Resolved<CargoOwner> requireCargoOwnerByAppId(String appId);
public void invalidate(long tenantId, IntegrationType type, long observedVersion);
```

Use `ConcurrentHashMap<CacheKey, CacheEntry>`, `System.nanoTime()`, and the configured 30-second TTL. `require` must fail unless the row is enabled and complete. `requireCargoOwnerByAppId` must use `selectTenantIdsByCargoOwnerAppId`, require exactly one tenant, then call the normal resolver and constant-time compare the configured appId. No other tenant may read tenant `100` legacy values.

- [ ] **Step 4: Publish after commit and subscribe in every instance**

The local listener must run `@TransactionalEventListener(phase = AFTER_COMMIT)`, invalidate the resolver, invalidate S3 resources when type is S3, and call:

```java
redisTemplate.convertAndSend(channel,
        event.tenantId() + ":" + event.type().name() + ":" + event.configVersion());
```

Configure a `RedisMessageListenerContainer` subscriber on the same channel. It parses the three-part payload, invalidates local resolver/S3 caches, rejects malformed messages without failing the container, and never publishes secrets. The TTL remains the fallback for lost pub/sub messages.

- [ ] **Step 5: Verify and commit**

Run: `mvn -Dtest=TenantIntegrationResolverTest test`

Expected: PASS; Mockito verifies Redis messages contain only tenant/type/version.

```bash
git add src/main/java/top/hetao/shiyuanticketmp/tenant/integration/service/TenantIntegrationResolver.java src/main/java/top/hetao/shiyuanticketmp/tenant/integration/config src/test/java/top/hetao/shiyuanticketmp/tenant/integration/service
git commit -m "feat: hot reload tenant integration configs"
```

### Task 6: Expose typed, permission-checked, sanitized APIs

**Files:**
- Create: `src/main/java/top/hetao/shiyuanticketmp/tenant/integration/controller/TenantIntegrationController.java`
- Delete: `src/main/java/top/hetao/shiyuanticketmp/tenant/setting/controller/TenantIntegrationSettingController.java`
- Delete: `src/main/java/top/hetao/shiyuanticketmp/tenant/setting/controller/dto/TenantIntegrationSettings.java`
- Delete: `src/main/java/top/hetao/shiyuanticketmp/tenant/setting/controller/dto/TenantIntegrationSettingsRequest.java`
- Test: `src/test/java/top/hetao/shiyuanticketmp/tenant/integration/controller/TenantIntegrationControllerTest.java`

- [ ] **Step 1: Write failing controller tests**

Use standalone MockMvc with mocked service. Assert `GET` never serializes a secret, unknown JSON keys return 400, each PUT calls only its fixed type, and a `ConflictException` is 409. Also assert the source contains no `websocket`, `QQ`, or `Map<String,Object> request` input.

- [ ] **Step 2: Run and verify failure**

Run: `mvn -Dtest=TenantIntegrationControllerTest test`

Expected: FAIL because the new controller does not exist.

- [ ] **Step 3: Implement fixed typed endpoints**

```java
@RestController
@RequestMapping("/api/admin/settings/integrations")
public class TenantIntegrationController {
    private final TenantIntegrationService service;

    public TenantIntegrationController(TenantIntegrationService service) {
        this.service = service;
    }

    @GetMapping @SaCheckPermission("settings:view")
    public Map<String,Object> getAll() {
        long tenantId = TenantContext.requireTenantId();
        List<View> views = Arrays.stream(IntegrationType.values())
                .map(type -> service.get(tenantId, type)).toList();
        return success(views, "查询成功");
    }

    @PutMapping("/dingtalk") @SaCheckPermission("settings:update")
    public Map<String,Object> updateDingTalk(@RequestBody DingTalkUpdate request) {
        return update(IntegrationType.DINGTALK, new UpdatePatch(
                request.expectedConfigVersion(), request.enabled(),
                fields("workOrderDetailBaseUrl", request.workOrderDetailBaseUrl()),
                secrets("accessToken", request.accessToken(), "signingSecret", request.signingSecret())));
    }
    @PutMapping("/cargo-owner") @SaCheckPermission("settings:update")
    public Map<String,Object> updateCargoOwner(@RequestBody CargoOwnerUpdate request) {
        return update(IntegrationType.CARGO_OWNER, new UpdatePatch(
                request.expectedConfigVersion(), request.enabled(),
                fields("targetUrl", request.targetUrl(), "receiveAppId", request.receiveAppId(),
                        "workOrderDetailBaseUrl", request.workOrderDetailBaseUrl(),
                        "inboundEnabled", request.inboundEnabled(),
                        "closeCallbackEnabled", request.closeCallbackEnabled()),
                secrets("authorization", request.authorization(),
                        "receivePrivateKey", request.receivePrivateKey())));
    }
    @PutMapping("/s3") @SaCheckPermission("settings:update")
    public Map<String,Object> updateS3(@RequestBody S3Update request) {
        return update(IntegrationType.S3, new UpdatePatch(
                request.expectedConfigVersion(), request.enabled(),
                fields("endpoint", request.endpoint(), "region", request.region(),
                        "bucket", request.bucket(), "pathStyleAccess", request.pathStyleAccess(),
                        "presignExpireSeconds", request.presignExpireSeconds()),
                secrets("accessKey", request.accessKey(), "secretKey", request.secretKey())));
    }
    @PutMapping("/express") @SaCheckPermission("settings:update")
    public Map<String,Object> updateExpress(@RequestBody ExpressUpdate request) {
        return update(IntegrationType.EXPRESS, new UpdatePatch(
                request.expectedConfigVersion(), request.enabled(),
                fields("apiUrl", request.apiUrl(), "timeoutSeconds", request.timeoutSeconds()),
                secrets("appCode", request.appCode())));
    }
    @PutMapping("/ai") @SaCheckPermission("settings:update")
    public Map<String,Object> updateAi(@RequestBody AiUpdate request) {
        return update(IntegrationType.AI, new UpdatePatch(
                request.expectedConfigVersion(), request.enabled(),
                fields("apiUrl", request.apiUrl(), "model", request.model(),
                        "timeoutSeconds", request.timeoutSeconds()),
                secrets("apiKey", request.apiKey())));
    }

    private Map<String,Object> update(IntegrationType type, UpdatePatch patch) {
        long tenantId = TenantContext.requireTenantId();
        long operatorId = Long.parseLong(StpUtil.getLoginIdAsString());
        return success(service.update(tenantId, operatorId, type, patch), "设置已保存");
    }

    private static Map<String,Object> fields(Object... pairs) {
        Map<String,Object> result = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) result.put((String) pairs[i], pairs[i + 1]);
        return result;
    }

    private static Map<String,SecretPatch> secrets(Object... pairs) {
        Map<String,SecretPatch> result = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) result.put((String) pairs[i], (SecretPatch) pairs[i + 1]);
        return result;
    }

    private static Map<String,Object> success(Object data, String message) {
        return Map.of("code", 200, "message", message, "data", data);
    }
}
```

Add the imports for `StpUtil`, `TenantContext`, the nested `IntegrationModels` records, `Arrays`, `LinkedHashMap`, `List`, and `Map`. The helpers explicitly name every public/secret key and never forward an input map from the request. Successful responses retain the repository convention `{code,message,data}`.

- [ ] **Step 4: Verify and commit**

Run: `mvn -Dtest=TenantIntegrationControllerTest test`

Expected: PASS.

```bash
git add src/main/java/top/hetao/shiyuanticketmp/tenant/integration/controller src/main/java/top/hetao/shiyuanticketmp/tenant/setting/controller src/test/java/top/hetao/shiyuanticketmp/tenant/integration/controller
git commit -m "feat: expose typed tenant integration APIs"
```

### Task 7: Switch DingTalk and cargo-owner Webhooks without changing behavior

**Files:**
- Modify: `src/main/java/top/hetao/shiyuanticketmp/webhook/sender/AbstractWebhookDispatcher.java`
- Modify: `src/main/java/top/hetao/shiyuanticketmp/webhook/sender/DingTalkDispatcher.java`
- Modify: `src/main/java/top/hetao/shiyuanticketmp/webhook/sender/CargoOwnerDispatcher.java`
- Modify: `src/main/java/top/hetao/shiyuanticketmp/webhook/sender/WebhookMessageAggregator.java`
- Modify: `src/main/java/top/hetao/shiyuanticketmp/webhook/receiver/CargoOwnerSignVerifier.java`
- Modify: `src/main/java/top/hetao/shiyuanticketmp/webhook/receiver/CargoOwnerWebhookController.java`
- Modify: `src/main/java/top/hetao/shiyuanticketmp/workorder/listener/WorkOrderWebhookListener.java`
- Modify: `src/main/java/top/hetao/shiyuanticketmp/webhook/deadletter/WebhookDeadLetterService.java`
- Test: `src/test/java/top/hetao/shiyuanticketmp/webhook/sender/WebhookTenantConfigTest.java`
- Test: `src/test/java/top/hetao/shiyuanticketmp/webhook/receiver/CargoOwnerTenantVerificationTest.java`

- [ ] **Step 1: Add regression tests before changing dispatchers**

Assert: aggregator keys retain tenant ID; each delivery and dead-letter retry passes the record/event tenant explicitly; DingTalk message remains markdown; cargo message remains `{message, room_id, senderStaffId}`; detail URLs remain unchanged; cargo verification still performs canonical JSON → MD5 → RSA private-key decrypt; a disabled/incomplete channel does not send; invalid Webhook URLs fail SSRF validation.

- [ ] **Step 2: Run focused tests and record the baseline**

Run: `mvn -Dtest=WebhookTenantLinkTest,WebhookTenantConfigTest,CargoOwnerTenantVerificationTest test`

Expected: new tests FAIL while existing `WebhookTenantLinkTest` passes.

- [ ] **Step 3: Make tenant ID an explicit dispatcher argument**

Change these signatures throughout abstract class, aggregator, and dead-letter retry:

```java
public void dispatch(long tenantId, String eventType, Object payload);
public DispatchResult retryRaw(long tenantId, String eventType, String eventId, byte[] rawBody);
protected void doDispatchWithRetry(long tenantId, String eventType, String eventId, byte[] body,
                                   String conversationId, String senderStaffId);
```

Resolve the current version once at the start of each delivery attempt; do not store a credential in a dead-letter row. Keep tenant ID, channel, conversation ID, senderStaffId, payload, retry counts, and current message format exactly as today.

- [ ] **Step 4: Replace `@Value` credentials**

DingTalk calls `resolver.require(tenantId, DINGTALK, DingTalk.class)` for access token, signing secret, and detail URL. Cargo calls the corresponding `CargoOwner` config and revalidates `targetUrl` immediately before send. Change `prepareBatchBody` to accept tenant ID so detail URLs are rendered from that tenant's current config:

```java
byte[] prepareBatchBody(long tenantId, List<WorkOrderEvent> events) throws Exception;
```

Remove only credential/business `@Value` fields. Keep platform retry/timeout/aggregator tuning properties in Spring because they are infrastructure, not tenant secrets.

- [ ] **Step 5: Resolve inbound cargo tenant by appId, then verify**

Change the verifier to:

```java
public VerificationResult verify(String appId, String sign, String body) {
    if (sign == null || sign.isBlank()) return new VerificationResult(null, "sign 请求头缺失");
    try {
        Resolved<CargoOwner> resolved = resolver.requireCargoOwnerByAppId(appId);
        String canonicalJson = JSONUtil.toJsonStr(JSONUtil.parse(body));
        String localMd5 = DigestUtil.md5Hex(canonicalJson);
        RSA rsa = new RSA(resolved.config().receivePrivateKey(), null);
        String remoteMd5 = rsa.decryptStr(sign, KeyType.PrivateKey, StandardCharsets.UTF_8);
        if (!MessageDigest.isEqual(localMd5.toLowerCase(Locale.ROOT).getBytes(StandardCharsets.US_ASCII),
                remoteMd5.toLowerCase(Locale.ROOT).getBytes(StandardCharsets.US_ASCII))) {
            return new VerificationResult(null, "签名验证失败");
        }
        return new VerificationResult(resolved.tenantId(), null);
    } catch (Exception exception) {
        log.warn("[货主验签] 验签失败，未输出请求或密钥内容");
        return new VerificationResult(null, "验签过程异常");
    }
}
public record VerificationResult(Long tenantId, String error) {}
```

The controller uses the verified tenant to look up `senderStaffId`, rejects a mapped user from a different tenant, enters `TenantContext.useTenant(verifiedTenantId)`, checks `inboundEnabled`, calls tenant-aware AI, and creates the order. It must not introduce WebSocket behavior or change request headers/body/response shapes. Replace the old V19 listener checks with `resolver.require` plus cargo `closeCallbackEnabled`/DingTalk availability.

- [ ] **Step 6: Verify and commit**

Run: `mvn -Dtest=WebhookTenantLinkTest,WebhookTenantConfigTest,CargoOwnerTenantVerificationTest test`

Expected: PASS with message-format snapshots unchanged.

```bash
git add src/main/java/top/hetao/shiyuanticketmp/webhook src/main/java/top/hetao/shiyuanticketmp/workorder/listener/WorkOrderWebhookListener.java src/test/java/top/hetao/shiyuanticketmp/webhook
git commit -m "refactor: resolve webhook config per tenant"
```

### Task 8: Switch S3 to a `(tenantId, configVersion)` resource cache

**Files:**
- Create: `src/main/java/top/hetao/shiyuanticketmp/file/service/TenantS3ResourceFactory.java`
- Modify: `src/main/java/top/hetao/shiyuanticketmp/file/service/FileService.java`
- Delete: `src/main/java/top/hetao/shiyuanticketmp/common/config/S3Config.java`
- Test: `src/test/java/top/hetao/shiyuanticketmp/file/service/TenantS3ResourceFactoryTest.java`
- Test: `src/test/java/top/hetao/shiyuanticketmp/file/service/FileServiceTenantS3Test.java`

- [ ] **Step 1: Write failing factory tests**

Assert two acquisitions at the same tenant/version share clients; another tenant/version builds distinct resources; invalidation retires old resources; a retired resource closes only after its last lease; incomplete/disabled config creates no AWS client.

- [ ] **Step 2: Run and verify failure**

Run: `mvn -Dtest=TenantS3ResourceFactoryTest,FileServiceTenantS3Test test`

Expected: FAIL because the factory does not exist.

- [ ] **Step 3: Implement the leased cache**

```java
public interface Lease extends AutoCloseable {
    S3Client client();
    S3Presigner presigner();
    String bucket();
    long presignExpireSeconds();
    @Override void close();
}
public Lease acquire(long tenantId);
public void invalidate(long tenantId, long observedVersion);
@PreDestroy public void closeAll();
```

Build AWS SDK v2 clients from `Resolved<S3>`, key the internal cache by `(tenantId, configVersion)`, reference-count active leases, mark old entries retired on invalidation, and close both `S3Client` and `S3Presigner` after the last lease closes. Continue using AWS SDK v2 lambda presign calls.

- [ ] **Step 4: Use one lease per FileService operation**

Replace singleton client/config fields. Every upload/download/HEAD/delete operation obtains `try (Lease s3 = resources.acquire(TenantContext.requireTenantId()))` and uses `s3.bucket()`, `s3.client()`, `s3.presigner()`, and `s3.presignExpireSeconds()`. Preserve current object key, ETag/version checks, two-step upload, transaction, and error messages.

- [ ] **Step 5: Verify and commit**

Run: `mvn -Dtest=TenantS3ResourceFactoryTest,FileServiceTenantS3Test test`

Expected: PASS and Mockito verifies closed old resources.

```bash
git add src/main/java/top/hetao/shiyuanticketmp/file/service src/main/java/top/hetao/shiyuanticketmp/common/config/S3Config.java src/test/java/top/hetao/shiyuanticketmp/file/service
git commit -m "refactor: resolve S3 resources per tenant"
```

### Task 9: Switch express and AI calls to the resolver

**Files:**
- Modify: `src/main/java/top/hetao/shiyuanticketmp/express/ExpressService.java`
- Modify: `src/main/java/top/hetao/shiyuanticketmp/ai/AiParseService.java`
- Modify: `src/main/java/top/hetao/shiyuanticketmp/ai/AiParseController.java`
- Modify: `src/main/java/top/hetao/shiyuanticketmp/webhook/receiver/CargoOwnerWebhookController.java`
- Test: `src/test/java/top/hetao/shiyuanticketmp/express/ExpressTenantConfigTest.java`
- Test: `src/test/java/top/hetao/shiyuanticketmp/ai/AiParseTenantConfigTest.java`

- [ ] **Step 1: Write failing tests**

Test that express uses the explicit tenant's URL/appCode/timeout and refuses disabled config; AI uses URL/apiKey/model/timeout for the explicit tenant; neither logs keys; cargo inbound passes its verified tenant into AI. Ensure one tenant's config is never reused for another.

- [ ] **Step 2: Run and verify failure**

Run: `mvn -Dtest=ExpressTenantConfigTest,AiParseTenantConfigTest test`

Expected: FAIL while current services still use `@Value`/constants.

- [ ] **Step 3: Change external-call signatures**

```java
public String parse(long tenantId, String text);
private ExpressTraceResponse doQueryExternal(long tenantId, String trackingNo,
                                             String mobileLast4, String cpCode);
```

At the top of `AiParseController.parse`, resolve availability before reading the existing result cache so disabling AI cannot be bypassed by a cached response. Pass `TenantContext.requireTenantId()` to `AiParseService`. Cargo inbound passes its verified tenant. Express public methods obtain `TenantContext.requireTenantId()` once and pass it down; only `doQueryExternal` requires enabled complete EXPRESS config, so existing DB/Redis trace reads remain usable when outbound querying is disabled.

- [ ] **Step 4: Remove business credentials from normal runtime**

Remove AI and express credential `@Value` fields and the hard-coded express URL. Keep retry counts, AI prompt, parsing, 418 behavior, rate limits, and express response/cache semantics unchanged. Do not remove the old property names yet: Task 10's explicit importer is their only remaining consumer.

- [ ] **Step 5: Verify and commit**

Run: `mvn -Dtest=ExpressTenantConfigTest,AiParseTenantConfigTest test`

Expected: PASS.

```bash
git add src/main/java/top/hetao/shiyuanticketmp/express/ExpressService.java src/main/java/top/hetao/shiyuanticketmp/ai src/main/java/top/hetao/shiyuanticketmp/webhook/receiver/CargoOwnerWebhookController.java src/test/java/top/hetao/shiyuanticketmp/express src/test/java/top/hetao/shiyuanticketmp/ai
git commit -m "refactor: resolve express and AI config per tenant"
```

### Task 10: Add the explicit, one-time tenant 100 legacy importer

**Files:**
- Create: `src/main/java/top/hetao/shiyuanticketmp/tenant/integration/migration/LegacyTenant100IntegrationImporter.java`
- Modify: `src/main/java/top/hetao/shiyuanticketmp/tenant/setting/mapper/SysTenantSettingMapper.java`
- Test: `src/test/java/top/hetao/shiyuanticketmp/tenant/integration/migration/LegacyTenant100IntegrationImporterTest.java`

- [ ] **Step 1: Write failing importer tests**

Cover: target is always 100; missing tenant aborts; all five legacy property groups map correctly; existing DB public/secret values win; old V19 switches map to DingTalk enabled and cargo inbound/close booleans; successful import writes marker and audit in the same transaction; a marker prevents a second execution; fingerprint/audit/log output contains no plaintext; SSL keys are ignored; no tenant other than 100 receives fallback.

- [ ] **Step 2: Run and verify failure**

Run: `mvn -Dtest=LegacyTenant100IntegrationImporterTest test`

Expected: FAIL because the importer does not exist.

- [ ] **Step 3: Implement an opt-in ApplicationRunner**

```java
@Component
public final class LegacyTenant100IntegrationImporter implements ApplicationRunner {
    static final long TARGET_TENANT_ID = 100L;
    static final String MARKER = "legacy-business-integrations-v1-tenant-100";

    @Override public void run(ApplicationArguments args) {
        if (!environment.getProperty("tenant-integration.legacy-import-enabled", Boolean.class, false)) return;
        transactionTemplate.executeWithoutResult(status -> importOnce());
    }
}
```

Read only these old names through Spring `Environment`: `webhook.dingtalk.access-token`, `webhook.dingtalk.secret`, `webhook.dingtalk.work-order-detail-base-url`, `webhook.cargo-owner.url`, `webhook.cargo-owner.authorization`, `webhook.cargo-owner.work-order-detail-base-url`, `webhook.cargo-owner.receive.app-id`, `webhook.cargo-owner.receive.private-key`, `s3.endpoint`, `s3.access-key`, `s3.secret-key`, `s3.region`, `s3.bucket`, `s3.path-style`, `s3.presign-expire-seconds`, `express.appcode`, `ai.parse.api-url`, `ai.parse.api-key`, `ai.parse.model`, and `ai.parse.timeout-seconds`.

Use existing fixed defaults for express URL (`https://kzexpress.market.alicloudapi.com/api-mall/api/express/query`) and timeout 15. Merge only missing fields, encrypt secrets via the normal service, and infer imported enabled state only when the merged config is complete. Read V19 `externalInboundEnabled`, `externalCloseCallbackEnabled`, and `dingTalkPushEnabled` for tenant 100; missing legacy switches mean true only inside this importer, never normal resolver behavior. Compute the stored fingerprint as SHA-256 over sorted `propertyName + ":" + SHA-256(value)` pairs. Never include values in logs, exceptions, marker, or audit. Do not read or import SSL properties.

- [ ] **Step 4: Test the temporary fallback boundary**

If `tenant-integration.legacy-fallback-tenant100-enabled=true`, resolver may build a database-first merged config only for tenant 100. Default is false. Add an assertion that tenant 101 with identical Spring properties remains disabled. The rollout in Task 12 turns this flag off immediately after import verification.

- [ ] **Step 5: Verify and commit**

Run: `mvn -Dtest=LegacyTenant100IntegrationImporterTest test`

Expected: PASS, including redaction assertions.

```bash
git add src/main/java/top/hetao/shiyuanticketmp/tenant/integration/migration src/main/java/top/hetao/shiyuanticketmp/tenant/setting/mapper/SysTenantSettingMapper.java src/test/java/top/hetao/shiyuanticketmp/tenant/integration/migration
git commit -m "feat: import legacy integrations into tenant 100"
```

### Task 11: Replace the settings UI with five independent typed forms

**Files:**
- Modify: `../front/src/api/admin/settings.js`
- Create: `../front/src/views/settings/integration-form.js`
- Modify: `../front/src/views/settings/index.vue`
- Create: `../front/tests/integration-settings.test.mjs`

- [ ] **Step 1: Write failing pure Node tests**

```javascript
import test from 'node:test'
import assert from 'node:assert/strict'
import { emptyForms, applyViews, buildPayload, resetSecrets } from '../src/views/settings/integration-form.js'

test('secret fields send keep clear and replace without echoing server secrets', () => {
  const forms = emptyForms()
  applyViews(forms, [{ type: 'AI', enabled: true, configVersion: '3',
    complete: true, config: { apiUrl: 'https://ai.example/v1', model: 'qwen-flash', timeoutSeconds: 30 },
    secretConfigured: { apiKey: true } }])
  assert.equal(forms.AI.apiKey, '')
  assert.equal(buildPayload('AI', forms.AI).apiKey, undefined)
  forms.AI.clearApiKey = true
  assert.deepEqual(buildPayload('AI', forms.AI).apiKey, { clear: true })
  forms.AI.clearApiKey = false
  forms.AI.apiKey = 'replacement'
  assert.deepEqual(buildPayload('AI', forms.AI).apiKey, { value: 'replacement' })
  resetSecrets(forms.AI)
  assert.equal(forms.AI.apiKey, '')
})

test('there is no QQ or websocket form', () => {
  assert.deepEqual(Object.keys(emptyForms()), ['DINGTALK','CARGO_OWNER','S3','EXPRESS','AI'])
})
```

- [ ] **Step 2: Run and verify failure**

Run from `../front/`: `node --test tests/integration-settings.test.mjs`

Expected: FAIL because `integration-form.js` does not exist.

- [ ] **Step 3: Update the API module**

```javascript
export function getIntegrations() {
  return request({ url: '/admin/settings/integrations', method: 'get' })
}

export function updateIntegration(type, data) {
  const paths = Object.freeze({
    DINGTALK: 'dingtalk', CARGO_OWNER: 'cargo-owner', S3: 's3', EXPRESS: 'express', AI: 'ai',
  })
  const path = paths[type]
  if (!path) throw new TypeError(`Unsupported integration type: ${type}`)
  return request({ url: `/admin/settings/integrations/${path}`, method: 'put', data })
}
```

- [ ] **Step 4: Implement form state and page behavior**

`emptyForms()` returns exactly the five forms and initializes every integration as disabled/version 0. `applyViews` validates all five typed responses. `buildPayload` copies only the type's public keys, includes `expectedConfigVersion` as the original string, and adds secret patches only for nonblank replacements or checked clears. The cargo form labels the existing custom cargo-owner Webhook, with outbound URL/authorization, inbound appId/private key, detail URL, inbound switch, and close-callback switch; it contains no QQ Bot or WebSocket field.

Replace the old three switches with five `el-collapse-item` or tabs. Each form displays Enabled, Complete/Incomplete, Config Version, public inputs, configured-secret badges, blank password inputs, and explicit clear checkboxes. Use `settings:view` and `settings:update`, disable controls during tenant switching/loading/saving, keep one snapshot per type, roll back only the failed type, and let `request.js` remain the only request-error toast source.

The existing active-tenant watcher must synchronously call:

```javascript
forms.value = emptyForms()
ready.value = false
snapshots.value = {}
```

Then reload after tenant switching finishes. On successful save, apply the returned view and clear all secret draft/clear fields.

- [ ] **Step 5: Verify and commit**

Run from `../front/`: `node --test tests/integration-settings.test.mjs`

Expected: PASS.

Run from `../front/`: `npm run build`

Expected: Vite exits 0 and emits `dist/` assets.

```bash
git -C ../front add src/api/admin/settings.js src/views/settings/index.vue src/views/settings/integration-form.js tests/integration-settings.test.mjs
git -C ../front commit -m "feat: manage typed tenant integrations"
```

### Task 12: Retire old runtime reads, sync Apifox, and verify rollout

**Files:**
- Delete: `src/main/java/top/hetao/shiyuanticketmp/tenant/setting/service/TenantIntegrationSettingService.java`
- Modify: `src/main/resources/application-dev.yml`
- Modify: `src/main/resources/application-prod.yml`
- Modify: `src/test/java/top/hetao/shiyuanticketmp/webhook/sender/WebhookTenantLinkTest.java`
- Modify: Apifox project `8260787` only; do not create a local `API.md`

- [ ] **Step 1: Prove no normal runtime credential reads remain**

Run:

```powershell
rg -n '@Value\("\$\{(webhook\.(dingtalk|cargo-owner)|s3\.|express\.appcode|ai\.parse)' src/main/java
```

Expected: no matches in DingTalk, cargo-owner, S3, express, AI, file, or listener runtime classes. Matches are allowed only inside the explicitly named legacy importer through `Environment`, not `@Value`.

Run:

```powershell
rg -n 'WebSocket|websocket|QQ Bot|QQ机器人' src/main ../front/src
```

Expected: no new integration implementation/UI matches.

- [ ] **Step 2: Run all focused and build verification**

Run from `backend/`:

```powershell
mvn -Dtest=IntegrationSchemaContractTest,IntegrationSecretCryptoTest,ExternalUrlValidatorTest,IntegrationModelsTest,TenantIntegrationServiceTest,TenantIntegrationResolverTest,TenantIntegrationControllerTest,WebhookTenantLinkTest,WebhookTenantConfigTest,CargoOwnerTenantVerificationTest,TenantS3ResourceFactoryTest,FileServiceTenantS3Test,ExpressTenantConfigTest,AiParseTenantConfigTest,LegacyTenant100IntegrationImporterTest test
mvn compile
```

Expected: all listed tests PASS and compile ends with `BUILD SUCCESS` without requiring MySQL/Redis.

Run from `../front/`:

```powershell
node --test tests/integration-settings.test.mjs
npm run build
```

Expected: Node tests PASS and Vite build exits 0.

- [ ] **Step 3: Sync the six API definitions in Apifox**

Run:

```powershell
apifox whoami
apifox endpoint list --project 8260787 --path-contains /api/admin/settings/integrations --page 1 --page-size 500
apifox cli-schema get endpoint-create
apifox cli-schema get endpoint-update
```

Expected: authenticated account is shown; existing matching endpoint IDs, if any, are listed; both payload schemas are printed. Create/update the GET and five PUT endpoints using schema-compliant JSON files in `$env:TEMP`, documenting 200 sanitized response, 400 invalid/incomplete input, 403 permissions, and 409 version conflict. For a new endpoint run `apifox cli-schema validate endpoint-create --file $payload` followed by `apifox endpoint create --project 8260787 --file $payload`; for an existing endpoint set `$endpointId` to the ID printed by the list command, then run `apifox cli-schema validate endpoint-update --file $payload` followed by `apifox endpoint update $endpointId --project 8260787 --file $payload`. Verify with the same endpoint-list command. Never include example plaintext secrets.

- [ ] **Step 4: Execute the production import and disable fallback**

Before deployment, provision `TENANT_INTEGRATION_ACTIVE_KEY_VERSION=1` and a random base64-encoded 32-byte `TENANT_INTEGRATION_MASTER_KEY_V1` from the platform secret manager. Apply the actual migration version selected in Task 1. Start one controlled instance once with `TENANT_INTEGRATION_LEGACY_IMPORT_ENABLED=true`; expected log output names tenant 100, five types, versions, and the fingerprint only. Verify GET views as tenant 100 report intended enabled/complete states and configured-secret booleans, then restart with both `TENANT_INTEGRATION_LEGACY_IMPORT_ENABLED=false` and `TENANT_INTEGRATION_LEGACY_FALLBACK_TENANT100_ENABLED=false`. A second import attempt must report the marker and perform zero updates. Verify a different tenant with no rows sees all five types disabled/incomplete.

- [ ] **Step 5: Remove old business keys from normal deployment configuration**

After the import and no-fallback restart succeeds, remove the old credential values from `application-dev.yml`, `application-prod.yml`, and deployment environment injection. Keep database, Redis, encryption key-ring, Webhook retry/worker tuning, and other platform infrastructure settings. Do not remove the V19 table in this release because the completed import marker and production rollback window must remain inspectable; normal runtime no longer reads it.

- [ ] **Step 6: Final commit**

```bash
git add src/main src/test
git commit -m "chore: complete tenant integration cutover"
```

Expected: `git status --short` is empty in `backend/`, and `git -C ../front status --short` is empty in `front/`.

## Acceptance checklist

- Cross-tenant normal reads/writes are tenant-interceptor scoped; inbound appId lookup returns one tenant and then re-enters the normal tenant resolver.
- GET/API/audit/log/exception output contains no access token, signing secret, authorization, private key, access key, secret key, appCode, API key, plaintext bundle, nonce, or master key.
- AES-256-GCM uses a fresh per-field nonce and AAD `tenantId:type:fieldName`; tampering and AAD mismatch fail closed.
- Missing rows are disabled. Enabled but incomplete configurations are rejected. There is no missing-row-true behavior after cutover.
- Every asynchronous Webhook aggregate, retry, and dead letter carries tenant ID explicitly.
- S3 resources are cached by tenant/version and old clients close safely after invalidation.
- Redis invalidation contains tenant/type/version only and local TTL covers lost messages.
- Tenant 100 is the only legacy import/fallback target; the import is merge-only, transactional, fingerprinted, audited without plaintext, and one-shot.
- `CARGO_OWNER` is the sole home of the requested QQ-related custom Webhook settings; no WebSocket capability exists.
- Frontend forms enforce `settings:view`/`settings:update`, clear stale state on tenant change, and implement secret keep/clear/replace without echoing secrets.
- Backend focused tests and `mvn compile`, frontend Node tests and `npm run build`, and Apifox project `8260787` synchronization all succeed.
