# Platform SSL and acme.sh Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 为平台共享域名实现仅全局管理员可管理、可选启用、私钥加密入库的内嵌 Tomcat 单端口 HTTP/HTTPS，并支持冷启动 fail-closed、运行期协议切换回滚、证书热重载及 acme.sh 自动导入。

**Architecture:** `platformssl` 模块以全局表保存目标协议、证书版本、切换操作和 ACME deploy token；启动期通过 JDBC 在 Connector 绑定前读取目标状态，运行期由单线程协调器独占同一个 `server.port` 完成 Connector 切换或 Tomcat 10.1.5 SSLHostConfig 热重载。前端提供独立的平台 SSL 页面；容器始终只监听 9860，由 Docker 映射 `443:9860`，应用不增加 80/443 Connector，也不引入 Nginx。

**Tech Stack:** Java 17、Spring Boot 3.0.2、embedded Tomcat 10.1.5、MyBatis-Plus 3.5.5、MySQL、AES-256-GCM、Bouncy Castle 1.85、JUnit 5/Mockito、Vue 3、Element Plus、Node `node:test`、Docker、acme.sh。

---

## File map and non-negotiable invariants

- 后端命令从 `backend/` 执行；前端命令从 `front/` 执行。本文位于后端仓库，因此前端路径统一写为 `../front/...`。
- 新建 `src/main/java/top/hetao/shiyuanticketmp/platformssl/`，按 `certificate`、`persistence`、`runtime`、`web`、`acme`、`migration` 分包；平台 SSL 不依赖租户业务配置模块。
- 容器内永远只有一个 Connector、一个 `server.port`：disabled 为 HTTP，enabled 为 HTTPS。不得新增 80/443 Connector，不得依赖 Nginx，不得声称 TLS 模式的同端口 HTTP 能返回 308。
- `platform_ssl_*` 均为无 `tenant_id` 的全局表，必须加入 `MybatisPlusConfig.GLOBAL_TABLES`。所有管理 API 同时要求 `GLOBAL_SYSTEM_ADMIN` 和 `platform-ssl:*` 权限，并允许全局管理员在未选择业务租户时访问。
- 首次升级时表不存在等同 disabled；仅 MySQL 1146/SQLState `42S02` 可降级为“表不存在”。数据库连接失败、SQL 错误、enabled 但证书缺失/过期/SAN 不匹配/无法解密都必须终止启动，不能暴露临时 HTTP。
- 私钥不落临时文件。PEM 在内存中解析为 PKCS12，PKCS12 整体经 AES-256-GCM 加密后入库；API、日志、异常和审计永不返回或记录 PEM、PKCS12、主密钥、deploy token 明文。
- 冷启动通过 `WebServerFactoryCustomizer<TomcatServletWebServerFactory>` 在主 Connector 创建和绑定前完成，不使用 `ApplicationReadyEvent` 切换协议。
- Tomcat 10.1.5 已在本仓库本地依赖核验：`SSLHostConfigCertificate#setCertificateKeystore(KeyStore)`、`AbstractHttp11Protocol#reloadSslHostConfig(String)`、`AbstractProtocol#closeServerSocketGraceful()`、`AbstractProtocol#awaitConnectionsClose(long)` 均存在。

### Task 1: Add the global schema, permissions, dependencies, and configuration contract

**Files:**
- Create: `src/main/resources/db/migration/V26__create_platform_ssl.sql`
- Modify: `pom.xml`
- Modify: `src/main/resources/application.properties`
- Modify: `src/main/java/top/hetao/shiyuanticketmp/common/config/MybatisPlusConfig.java`
- Test: `src/test/java/top/hetao/shiyuanticketmp/platformssl/PlatformSslSchemaContractTest.java`

- [ ] **Step 1: Write a RED schema/config contract test**

```java
class PlatformSslSchemaContractTest {
    private final Path migration = Path.of("src/main/resources/db/migration/V26__create_platform_ssl.sql");

    @Test
    void migrationDefinesSingletonConfigCertificatesOperationsAndHashedTokens() throws Exception {
        String sql = Files.readString(migration);
        assertThat(sql).contains("CREATE TABLE IF NOT EXISTS `platform_ssl_config`");
        assertThat(sql).contains("CREATE TABLE IF NOT EXISTS `platform_ssl_certificate`");
        assertThat(sql).contains("CREATE TABLE IF NOT EXISTS `platform_ssl_operation`");
        assertThat(sql).contains("CREATE TABLE IF NOT EXISTS `platform_ssl_deploy_token`");
        assertThat(sql).contains("`pkcs12_ciphertext` LONGBLOB", "`token_hash` BINARY(32)");
        assertThat(sql).doesNotContain("`private_key`", "`tenant_id` BIGINT");
        assertThat(sql).contains("'platform-ssl:view'", "'platform-ssl:update'");
    }
}
```

- [ ] **Step 2: Run the RED test**

Run from `backend/`: `mvn -Dtest=PlatformSslSchemaContractTest test`

Expected: FAIL because V26 does not exist.

- [ ] **Step 3: Add Bouncy Castle and the V26 migration**

Add `org.bouncycastle:bcpkix-jdk18on:1.85` to `pom.xml`. The migration must create:

```sql
CREATE TABLE IF NOT EXISTS `platform_ssl_config` (
  `id` BIGINT NOT NULL,
  `target_enabled` TINYINT(1) NOT NULL DEFAULT 0,
  `platform_domain` VARCHAR(253) NULL,
  `external_https_url` VARCHAR(512) NULL,
  `active_certificate_id` BIGINT NULL,
  `config_version` BIGINT NOT NULL DEFAULT 0,
  `runtime_protocol` VARCHAR(8) NOT NULL DEFAULT 'HTTP',
  `last_load_status` VARCHAR(32) NOT NULL DEFAULT 'DISABLED',
  `last_error_summary` VARCHAR(500) NULL,
  `legacy_imported_at` DATETIME NULL,
  `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS `platform_ssl_certificate` (
  `id` BIGINT NOT NULL,
  `fingerprint_sha256` CHAR(64) NOT NULL,
  `subject_dn` VARCHAR(500) NOT NULL,
  `san_json` JSON NOT NULL,
  `not_before` DATETIME NOT NULL,
  `not_after` DATETIME NOT NULL,
  `key_algorithm` VARCHAR(16) NOT NULL,
  `pkcs12_ciphertext` LONGBLOB NOT NULL,
  `pkcs12_nonce` BINARY(12) NOT NULL,
  `key_version` VARCHAR(32) NOT NULL,
  `status` VARCHAR(32) NOT NULL,
  `source` VARCHAR(16) NOT NULL,
  `error_summary` VARCHAR(500) NULL,
  `created_by` BIGINT NULL,
  `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_platform_ssl_certificate_fingerprint` (`fingerprint_sha256`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS `platform_ssl_operation` (
  `id` BIGINT NOT NULL,
  `operation_type` VARCHAR(32) NOT NULL,
  `previous_enabled` TINYINT(1) NOT NULL,
  `target_enabled` TINYINT(1) NOT NULL,
  `status` VARCHAR(32) NOT NULL,
  `reconnect_url` VARCHAR(512) NULL,
  `error_summary` VARCHAR(500) NULL,
  `requested_by` BIGINT NOT NULL,
  `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `completed_at` DATETIME NULL,
  PRIMARY KEY (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS `platform_ssl_deploy_token` (
  `token_id` CHAR(36) NOT NULL,
  `token_hash` BINARY(32) NOT NULL,
  `scope` VARCHAR(32) NOT NULL,
  `expires_at` DATETIME NOT NULL,
  `revoked_at` DATETIME NULL,
  `created_by` BIGINT NOT NULL,
  `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`token_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

INSERT IGNORE INTO `platform_ssl_config` (`id`) VALUES (1);
INSERT IGNORE INTO `sys_permission` (`id`, `permission_code`, `permission_name`) VALUES
  (20260714000000031, 'platform-ssl:view', '查看平台 SSL'),
  (20260714000000032, 'platform-ssl:update', '管理平台 SSL');
INSERT IGNORE INTO `sys_role_permission` (`role_id`, `permission_id`)
SELECT r.id, p.id FROM sys_role r JOIN sys_permission p
  ON p.permission_code IN ('platform-ssl:view', 'platform-ssl:update')
WHERE r.tenant_id = 0 AND r.role_code = 'GLOBAL_SYSTEM_ADMIN' AND r.deleted = 0;
```

Add all four table names to `GLOBAL_TABLES`. Add non-secret defaults to `application.properties`:

```properties
platform.ssl.switch-delay-ms=1000
platform.ssl.drain-timeout-ms=10000
platform.ssl.bootstrap-trusted-cidrs=127.0.0.0/8,::1/128
platform.secret.active-key-version=v1
platform.secret.keys.v1=${PLATFORM_SECRET_KEY_V1:}
```

- [ ] **Step 4: Run the test and compile**

Run: `mvn -Dtest=PlatformSslSchemaContractTest test && mvn compile`

Expected: PASS; compile resolves Bouncy Castle 1.85. Inspect the dependency tree with `mvn dependency:tree -Dincludes=org.bouncycastle` and expect one consistent 1.85 family.

- [ ] **Step 5: Commit**

```bash
git add pom.xml src/main/resources/application.properties src/main/resources/db/migration/V26__create_platform_ssl.sql src/main/java/top/hetao/shiyuanticketmp/common/config/MybatisPlusConfig.java src/test/java/top/hetao/shiyuanticketmp/platformssl/PlatformSslSchemaContractTest.java
git commit -m "feat: add platform ssl persistence schema"
```

### Task 2: Parse, validate, package, and encrypt certificate material

**Files:**
- Create: `src/main/java/top/hetao/shiyuanticketmp/platformssl/PlatformSslException.java`
- Create: `src/main/java/top/hetao/shiyuanticketmp/platformssl/PlatformSslConflictException.java`
- Create: `src/main/java/top/hetao/shiyuanticketmp/platformssl/certificate/PlatformSecretProperties.java`
- Create: `src/main/java/top/hetao/shiyuanticketmp/platformssl/certificate/AesGcmSecretCodec.java`
- Create: `src/main/java/top/hetao/shiyuanticketmp/platformssl/certificate/EncryptedPayload.java`
- Create: `src/main/java/top/hetao/shiyuanticketmp/platformssl/certificate/PlatformCertificateMaterial.java`
- Create: `src/main/java/top/hetao/shiyuanticketmp/platformssl/certificate/PlatformCertificateParser.java`
- Create: `src/test/java/top/hetao/shiyuanticketmp/platformssl/certificate/TestCertificateFactory.java`
- Create: `src/test/java/top/hetao/shiyuanticketmp/platformssl/certificate/PlatformCertificateParserTest.java`
- Create: `src/test/java/top/hetao/shiyuanticketmp/platformssl/certificate/AesGcmSecretCodecTest.java`

- [ ] **Step 1: Write RED tests for RSA, ECC, SAN, chain, mismatch, expiry, and GCM tampering**

```java
@ParameterizedTest
@ValueSource(strings = {"RSA", "EC"})
void acceptsMatchingRsaAndEcChains(String algorithm) {
    TestCertificateFactory.PemPair pair = TestCertificateFactory.issue("ticket.example.com", algorithm);
    PlatformCertificateMaterial material = parser.parseAndValidate(
        pair.fullChainPem(), pair.privateKeyPem(), "ticket.example.com", Clock.systemUTC());
    assertThat(material.keyStore().containsAlias("platform")).isTrue();
    assertThat(material.sans()).contains("ticket.example.com");
    assertThat(material.keyAlgorithm()).isEqualTo(algorithm);
}

@Test
void rejectsMismatchedPrivateKey() {
    var certificate = TestCertificateFactory.issue("ticket.example.com", "RSA");
    var otherKey = TestCertificateFactory.issue("ticket.example.com", "RSA");
    assertThatThrownBy(() -> parser.parseAndValidate(
        certificate.fullChainPem(), otherKey.privateKeyPem(), "ticket.example.com", clock))
        .isInstanceOf(PlatformSslException.class)
        .hasMessage("证书与私钥不匹配");
}

@Test
void rejectsExpiredCertificate() {
    var pair = TestCertificateFactory.issueExpired("ticket.example.com", "RSA", clock.instant());
    assertThatThrownBy(() -> parser.parseAndValidate(
        pair.fullChainPem(), pair.privateKeyPem(), "ticket.example.com", clock))
        .isInstanceOf(PlatformSslException.class).hasMessage("证书不在有效期内");
}

@Test
void rejectsDomainOutsideSanAndMultiLabelWildcard() {
    var exact = TestCertificateFactory.issue("other.example.com", "EC");
    assertThatThrownBy(() -> parser.parseAndValidate(
        exact.fullChainPem(), exact.privateKeyPem(), "ticket.example.com", clock))
        .isInstanceOf(PlatformSslException.class).hasMessage("证书 SAN 不覆盖平台域名");
    var wildcard = TestCertificateFactory.issue("*.example.com", "EC");
    assertThat(parser.parseAndValidate(wildcard.fullChainPem(), wildcard.privateKeyPem(),
        "a.example.com", clock).sans()).contains("*.example.com");
    assertThatThrownBy(() -> parser.parseAndValidate(wildcard.fullChainPem(),
        wildcard.privateKeyPem(), "a.b.example.com", clock))
        .isInstanceOf(PlatformSslException.class);
}

@Test
void rejectsBrokenChainSignature() {
    var broken = TestCertificateFactory.issueWithUnrelatedIssuer("ticket.example.com", "RSA");
    assertThatThrownBy(() -> parser.parseAndValidate(
        broken.fullChainPem(), broken.privateKeyPem(), "ticket.example.com", clock))
        .isInstanceOf(PlatformSslException.class).hasMessage("证书链校验失败");
}

@Test
void gcmBindsCiphertextToCertificateFingerprint() {
    EncryptedPayload payload = codec.encrypt(bytes, "platform-ssl:ABC");
    payload.ciphertext()[0] ^= 1;
    assertThatThrownBy(() -> codec.decrypt(payload, "platform-ssl:ABC"))
        .isInstanceOf(GeneralSecurityException.class);
    assertThatThrownBy(() -> codec.decrypt(original, "platform-ssl:OTHER"))
        .isInstanceOf(GeneralSecurityException.class);
}
```

- [ ] **Step 2: Run RED tests**

Run: `mvn -Dtest=PlatformCertificateParserTest,AesGcmSecretCodecTest test`

Expected: FAIL because parser and codec do not exist.

- [ ] **Step 3: Implement the certificate boundary**

Use `PEMParser` and `JcaPEMKeyConverter` so acme.sh PKCS#8, PKCS#1 RSA, and SEC1 EC keys are supported. The parser must cap each multipart input at 1 MiB, require at least one X.509 certificate, order leaf-to-issuer, call `checkValidity`, verify every adjacent signature, compare public keys by signing/verifying a random challenge, validate DNS/IP SAN, and write an in-memory PKCS12 with alias `platform` and empty entry password:

```java
public record PlatformCertificateMaterial(
    KeyStore keyStore, byte[] pkcs12, String fingerprintSha256,
    String subjectDn, List<String> sans, Instant notBefore, Instant notAfter,
    String keyAlgorithm) {}

KeyStore store = KeyStore.getInstance("PKCS12");
store.load(null, EMPTY_PASSWORD);
store.setKeyEntry("platform", privateKey, EMPTY_PASSWORD, chain.toArray(Certificate[]::new));
ByteArrayOutputStream out = new ByteArrayOutputStream();
store.store(out, EMPTY_PASSWORD);
```

Define `PlatformSslException extends RuntimeException` for safe 400 messages and `PlatformSslConflictException extends RuntimeException` for 409 conflicts before the certificate classes use them. `AesGcmSecretCodec` must decode the configured active key from Base64, require exactly 32 bytes, use a fresh 12-byte nonce and 128-bit tag, and bind AAD to `platform-ssl:<fingerprint>`. Missing keys are tolerated while SSL is disabled, but encryption/decryption calls fail with a generic `PlatformSslException` and never include key/ciphertext content.

- [ ] **Step 4: Run GREEN tests**

Run: `mvn -Dtest=PlatformCertificateParserTest,AesGcmSecretCodecTest test`

Expected: PASS for RSA and ECC; mismatch, expiry, SAN, chain and GCM tamper cases all fail closed.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/top/hetao/shiyuanticketmp/platformssl/PlatformSslException.java src/main/java/top/hetao/shiyuanticketmp/platformssl/PlatformSslConflictException.java src/main/java/top/hetao/shiyuanticketmp/platformssl/certificate src/test/java/top/hetao/shiyuanticketmp/platformssl/certificate
git commit -m "feat: validate and encrypt platform certificates"
```

### Task 3: Persist certificate versions and load cold-start state without MyBatis/Flyway ordering assumptions

**Files:**
- Create: `src/main/java/top/hetao/shiyuanticketmp/platformssl/persistence/PlatformSslConfig.java`
- Create: `src/main/java/top/hetao/shiyuanticketmp/platformssl/persistence/PlatformSslCertificate.java`
- Create: `src/main/java/top/hetao/shiyuanticketmp/platformssl/persistence/PlatformSslOperation.java`
- Create: `src/main/java/top/hetao/shiyuanticketmp/platformssl/persistence/PlatformSslConfigMapper.java`
- Create: `src/main/java/top/hetao/shiyuanticketmp/platformssl/persistence/PlatformSslCertificateMapper.java`
- Create: `src/main/java/top/hetao/shiyuanticketmp/platformssl/persistence/PlatformSslOperationMapper.java`
- Create: `src/main/java/top/hetao/shiyuanticketmp/platformssl/runtime/PlatformSslStartupState.java`
- Create: `src/main/java/top/hetao/shiyuanticketmp/platformssl/runtime/PlatformSslStartupException.java`
- Create: `src/main/java/top/hetao/shiyuanticketmp/platformssl/runtime/PlatformSslProtocol.java`
- Create: `src/main/java/top/hetao/shiyuanticketmp/platformssl/runtime/PlatformSslBootstrapLoader.java`
- Create: `src/test/java/top/hetao/shiyuanticketmp/platformssl/runtime/BootstrapRows.java`
- Create: `src/test/java/top/hetao/shiyuanticketmp/platformssl/runtime/PlatformSslBootstrapLoaderTest.java`

- [ ] **Step 1: Write RED bootstrap tests**

```java
@Test
void missingTableMeansDisabled() {
    SQLException missing = new SQLException("missing", "42S02", 1146);
    when(jdbc.query(anyString(), any(ResultSetExtractor.class)))
        .thenThrow(new BadSqlGrammarException("bootstrap", "select", missing));
    assertThat(loader.load().enabled()).isFalse();
}

@Test
void databaseUnavailableIsNotTreatedAsMissingTable() {
    SQLException unavailable = new SQLException("down", "08S01", 0);
    when(jdbc.query(anyString(), any(ResultSetExtractor.class)))
        .thenThrow(new DataAccessResourceFailureException("down", unavailable));
    assertThatThrownBy(loader::load).isInstanceOf(PlatformSslStartupException.class);
}

@Test
void disabledOrAbsentRowsReturnHttpWithoutDecrypting() {
    when(jdbc.query(anyString(), any(ResultSetExtractor.class)))
        .thenReturn(null, BootstrapRows.disabled());
    assertThat(loader.load().enabled()).isFalse();
    assertThat(loader.load().enabled()).isFalse();
    verifyNoInteractions(codec);
}

@Test
void everyEnabledInvalidStateFailsClosed() {
    for (BootstrapRow row : List.of(BootstrapRows.withoutCertificate(),
            BootstrapRows.undecryptable(), BootstrapRows.expired(), BootstrapRows.wrongSan())) {
        reset(jdbc);
        when(jdbc.query(anyString(), any(ResultSetExtractor.class))).thenReturn(row);
        assertThatThrownBy(loader::load).isInstanceOf(PlatformSslStartupException.class);
    }
}

@Test
void enabledValidStateReturnsTlsMaterial() throws Exception {
    when(jdbc.query(anyString(), any(ResultSetExtractor.class))).thenReturn(BootstrapRows.valid());
    PlatformSslStartupState state = loader.load();
    assertThat(state.enabled()).isTrue();
    assertThat(state.material().keyStore().containsAlias("platform")).isTrue();
}
```

- [ ] **Step 2: Run RED test**

Run: `mvn -Dtest=PlatformSslBootstrapLoaderTest test`

Expected: FAIL because the JDBC bootstrap loader does not exist.

- [ ] **Step 3: Implement entities/mappers and a direct-JDBC bootstrap reader**

Define `PlatformSslProtocol` as `HTTP`/`HTTPS` and `PlatformSslStartupException extends IllegalStateException`. Normal management uses MyBatis mappers. `PlatformSslBootstrapLoader` uses `JdbcTemplate` only, because it runs before the web Connector and must not assume Flyway/MyBatis initialization ordering:

```java
public PlatformSslStartupState load() {
    try {
        BootstrapRow row = jdbc.query(CONFIG_AND_CERT_SQL, rs -> rs.next() ? map(rs) : null);
        if (row == null || !row.targetEnabled()) return PlatformSslStartupState.http();
        if (row.ciphertext() == null) throw new PlatformSslStartupException("SSL 已启用但没有活动证书");
        byte[] pkcs12 = codec.decrypt(row.encryptedPayload(), "platform-ssl:" + row.fingerprint());
        PlatformCertificateMaterial material = parser.loadStoredAndRevalidate(pkcs12, row.domain(), clock);
        return PlatformSslStartupState.https(material);
    } catch (DataAccessException ex) {
        if (isMysqlMissingTable(ex)) return PlatformSslStartupState.http();
        throw new PlatformSslStartupException("无法读取平台 SSL 启动状态", ex);
    }
}
```

`isMysqlMissingTable` must walk causes and accept only error code 1146 or SQLState `42S02`; no catch-all fallback. Mapper updates use `WHERE id=1 AND config_version=#{expectedVersion}` and increment `config_version`, allowing later API 409 handling.

- [ ] **Step 4: Run GREEN test and mapper compilation**

Run: `mvn -Dtest=PlatformSslBootstrapLoaderTest test && mvn compile`

Expected: PASS; database outage and enabled-invalid cases throw, while only missing table/row/disabled return HTTP.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/top/hetao/shiyuanticketmp/platformssl/persistence src/main/java/top/hetao/shiyuanticketmp/platformssl/runtime/PlatformSslStartupState.java src/main/java/top/hetao/shiyuanticketmp/platformssl/runtime/PlatformSslBootstrapLoader.java src/test/java/top/hetao/shiyuanticketmp/platformssl/runtime/PlatformSslBootstrapLoaderTest.java
git commit -m "feat: load platform ssl state before connector binding"
```

### Task 4: Configure the first Tomcat Connector as HTTP or HTTPS before binding

**Files:**
- Create: `src/main/java/top/hetao/shiyuanticketmp/platformssl/runtime/TomcatTlsConfigurer.java`
- Create: `src/main/java/top/hetao/shiyuanticketmp/platformssl/runtime/PlatformSslWebServerCustomizer.java`
- Create: `src/test/java/top/hetao/shiyuanticketmp/platformssl/runtime/TomcatTlsConfigurerTest.java`
- Create: `src/test/java/top/hetao/shiyuanticketmp/platformssl/runtime/PlatformSslColdStartTest.java`

- [ ] **Step 1: Write RED connector tests**

```java
@Test
void httpsUsesTheExistingPortAndInMemoryKeyStore() {
    Connector connector = new Connector(Http11NioProtocol.class.getName());
    connector.setPort(9860);
    configurer.configureHttps(connector, material);
    AbstractHttp11Protocol<?> protocol = (AbstractHttp11Protocol<?>) connector.getProtocolHandler();
    assertThat(connector.getPort()).isEqualTo(9860);
    assertThat(connector.getScheme()).isEqualTo("https");
    assertThat(connector.getSecure()).isTrue();
    assertThat(protocol.isSSLEnabled()).isTrue();
    assertThat(protocol.findSslHostConfigs()).hasSize(1);
    assertThat(protocol.findSslHostConfigs()[0].getCertificates().iterator().next()
        .getCertificateKeystore().containsAlias("platform")).isTrue();
}

@Test
void disabledCustomizerLeavesThePrimaryConnectorAsHttp() {
    TomcatServletWebServerFactory factory = new TomcatServletWebServerFactory(9860);
    new PlatformSslWebServerCustomizer(() -> PlatformSslStartupState.http(), configurer).customize(factory);
    assertThat(factory.getAdditionalTomcatConnectors()).isEmpty();
    assertThat(factory.getPort()).isEqualTo(9860);
    assertThat(factory.getSsl()).isNull();
}

@Test
void enabledCustomizerSecuresThePrimaryConnectorWithoutAddingHttp() {
    TomcatServletWebServerFactory factory = new TomcatServletWebServerFactory(9860);
    new PlatformSslWebServerCustomizer(() -> PlatformSslStartupState.https(material), configurer)
        .customize(factory);
    assertThat(factory.getAdditionalTomcatConnectors()).isEmpty();
    assertThat(factory.getTomcatConnectorCustomizers()).hasSize(1);
}
```

- [ ] **Step 2: Run RED tests**

Run: `mvn -Dtest=TomcatTlsConfigurerTest,PlatformSslColdStartTest test`

Expected: FAIL because the customizer/configurer do not exist.

- [ ] **Step 3: Implement the exact Tomcat 10.1.5 setup**

```java
public void configureHttps(Connector connector, PlatformCertificateMaterial material) {
    connector.setScheme("https");
    connector.setSecure(true);
    AbstractHttp11Protocol<?> protocol = (AbstractHttp11Protocol<?>) connector.getProtocolHandler();
    protocol.setSSLEnabled(true);
    SSLHostConfig host = new SSLHostConfig();
    host.setHostName("_default_");
    SSLHostConfigCertificate certificate =
        new SSLHostConfigCertificate(host, SSLHostConfigCertificate.Type.UNDEFINED);
    certificate.setCertificateKeyAlias("platform");
    certificate.setCertificateKeyPassword("");
    certificate.setCertificateKeystore(material.keyStore());
    host.addCertificate(certificate);
    protocol.addSslHostConfig(host);
}
```

`PlatformSslWebServerCustomizer` uses `@Order(Ordered.LOWEST_PRECEDENCE)` so it is authoritative after Boot property customizers, calls `factory.setSsl(null)` to prevent legacy `server.ssl.*` from creating a second source of truth, then adds one primary connector customizer based on `bootstrapLoader.load()`. Never call `addAdditionalTomcatConnectors`.

- [ ] **Step 4: Run GREEN tests and a local socket smoke test**

Run: `mvn -Dtest=TomcatTlsConfigurerTest,PlatformSslColdStartTest test`

Expected: PASS. With test database state disabled, start on a random port and verify HTTP succeeds. With valid enabled state, first successful socket handshake must be TLS; an HTTP client to that port must fail protocol parsing and must not receive 308.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/top/hetao/shiyuanticketmp/platformssl/runtime/TomcatTlsConfigurer.java src/main/java/top/hetao/shiyuanticketmp/platformssl/runtime/PlatformSslWebServerCustomizer.java src/test/java/top/hetao/shiyuanticketmp/platformssl/runtime/TomcatTlsConfigurerTest.java src/test/java/top/hetao/shiyuanticketmp/platformssl/runtime/PlatformSslColdStartTest.java
git commit -m "feat: select http or tls before tomcat binds"
```

### Task 5: Add serialized runtime protocol switching, rollback, and certificate hot reload

**Files:**
- Create: `src/main/java/top/hetao/shiyuanticketmp/platformssl/runtime/TomcatRuntimeHandle.java`
- Create: `src/main/java/top/hetao/shiyuanticketmp/platformssl/runtime/TomcatConnectorFactory.java`
- Create: `src/main/java/top/hetao/shiyuanticketmp/platformssl/runtime/PlatformSslRuntimeCoordinator.java`
- Create: `src/main/java/top/hetao/shiyuanticketmp/platformssl/runtime/PlatformSslRuntimeConfiguration.java`
- Create: `src/test/java/top/hetao/shiyuanticketmp/platformssl/runtime/PlatformSslRuntimeCoordinatorTest.java`

- [ ] **Step 1: Write RED rollback and reload tests**

```java
@Test
void switchUsesOrderedDrainAndSamePortReplacement() throws Exception {
    coordinator.execute(operation(HTTP, HTTPS));
    InOrder order = inOrder(oldConnector, oldProtocol, service, replacement);
    order.verify(oldConnector).pause();
    order.verify(oldProtocol).closeServerSocketGraceful();
    order.verify(oldProtocol).awaitConnectionsClose(10_000L);
    order.verify(oldConnector).stop();
    order.verify(service).removeConnector(oldConnector);
    order.verify(service).addConnector(replacement);
    order.verify(replacement).start();
    assertThat(replacement.getPort()).isEqualTo(oldConnector.getPort());
}

@Test
void replacementFailureRestartsOldConnectorAndRestoresTarget() throws Exception {
    doThrow(new LifecycleException("bind failed")).when(replacement).start();
    coordinator.execute(operation(HTTP, HTTPS));
    verify(service).addConnector(oldConnector);
    verify(oldConnector).start();
    verify(repository).failOperationAndRestoreTarget(operationId, false, "bind failed");
}

@Test
void concurrentSwitchIsRejected() {
    when(repository.hasPendingOperation()).thenReturn(true);
    assertThatThrownBy(() -> coordinator.acceptSwitch(true, actorId))
        .isInstanceOf(PlatformSslConflictException.class);
}

@Test
void reloadUsesDefaultHostWithoutRestartAndFailureRestoresOldKeyStore() {
    coordinator.reloadCertificate(newMaterial);
    verify(protocol).reloadSslHostConfig("_default_");
    verify(connector, never()).stop();
    doThrow(new IllegalArgumentException("bad key")).doNothing()
        .when(protocol).reloadSslHostConfig("_default_");
    assertThatThrownBy(() -> coordinator.reloadCertificate(badMaterial))
        .isInstanceOf(PlatformSslException.class);
    assertThat(activeCertificate.getCertificateKeystore()).isSameAs(oldKeyStore);
}
```

- [ ] **Step 2: Run RED test**

Run: `mvn -Dtest=PlatformSslRuntimeCoordinatorTest test`

Expected: FAIL because no runtime coordinator exists.

- [ ] **Step 3: Capture Tomcat and implement same-port replacement**

`TomcatRuntimeHandle` listens for `ServletWebServerInitializedEvent`, requires `TomcatWebServer`, and captures `Tomcat#getService()` plus the primary Connector. Use a dedicated single-thread `ScheduledExecutorService`; enqueue switches after `platform.ssl.switch-delay-ms` so the 202 response can flush.

The coordinator sequence is fixed:

```java
old.pause();
AbstractProtocol<?> oldProtocol = (AbstractProtocol<?>) old.getProtocolHandler();
oldProtocol.closeServerSocketGraceful();
oldProtocol.awaitConnectionsClose(drainTimeoutMs);
old.stop();
service.removeConnector(old);
Connector replacement = connectorFactory.recreate(old, targetState); // same port/address/tuning
service.addConnector(replacement);
try {
    replacement.start();
    handle.replace(replacement);
    old.destroy();
    repository.completeOperationAndRuntimeState(operationId, targetEnabled);
} catch (Exception switchFailure) {
    safeStopRemoveDestroy(replacement);
    service.addConnector(old);
    old.start();
    handle.replace(old);
    repository.failOperationAndRestoreTarget(operationId, previousEnabled, summarize(switchFailure));
}
```

`TomcatConnectorFactory.recreate` copies port, address, connection timeout, keepalive, max threads/connections, accept count, header sizes, compression and URI encoding from the old `AbstractHttp11Protocol`; it then applies HTTP or `TomcatTlsConfigurer.configureHttps`. It must never change `server.port` or add another concurrently bound port.

For renewal, mutate the existing default certificate's in-memory KeyStore and call `AbstractHttp11Protocol.reloadSslHostConfig("_default_")`. Save old KeyStore, reload new, and on any exception restore old KeyStore and reload `_default_` again before reporting failure. Do not stop the Connector or Spring context.

- [ ] **Step 4: Run GREEN tests**

Run: `mvn -Dtest=PlatformSslRuntimeCoordinatorTest test`

Expected: PASS, including exact ordered calls, serialized switch, rollback to the old live connector, and hot reload without connector restart.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/top/hetao/shiyuanticketmp/platformssl/runtime src/test/java/top/hetao/shiyuanticketmp/platformssl/runtime/PlatformSslRuntimeCoordinatorTest.java
git commit -m "feat: switch tomcat protocol with rollback"
```

### Task 6: Expose platform-admin status, upload, configuration, switch, and operation APIs

**Files:**
- Create: `src/main/java/top/hetao/shiyuanticketmp/platformssl/PlatformSslService.java`
- Create: `src/main/java/top/hetao/shiyuanticketmp/platformssl/web/PlatformSslAdminController.java`
- Create: `src/main/java/top/hetao/shiyuanticketmp/platformssl/web/dto/PlatformSslDtos.java`
- Modify: `src/main/java/top/hetao/shiyuanticketmp/common/exception/GlobalExceptionHandler.java`
- Modify: `src/main/java/top/hetao/shiyuanticketmp/common/config/SaTokenConfig.java`
- Test: `src/test/java/top/hetao/shiyuanticketmp/platformssl/web/PlatformSslAdminControllerTest.java`

- [ ] **Step 1: Write RED MockMvc authorization and response tests**

Cover:

```text
GET  /api/admin/platform-ssl
PUT  /api/admin/platform-ssl/config
GET  /api/admin/platform-ssl/certificates
POST /api/admin/platform-ssl/certificates       multipart fullchain/privateKey
POST /api/admin/platform-ssl/protocol-switch    { enabled, configVersion }
GET  /api/admin/platform-ssl/operations/{id}
```

Tests must prove non-global admins receive 403 even if manually granted the permission; GET never contains `pkcs12`, `ciphertext`, `nonce`, `privateKey`; upload rejects oversized/invalid/mismatched/expired/wrong-SAN material with 400; stale `configVersion` and concurrent switch return 409; enabled without a valid active certificate returns 400; switch returns HTTP 202 with `{operationId,targetProtocol,reconnectUrl,startsAfterMillis}`.

- [ ] **Step 2: Run RED controller tests**

Run: `mvn -Dtest=PlatformSslAdminControllerTest test`

Expected: FAIL because controller/service/exception mappings do not exist.

- [ ] **Step 3: Implement typed DTOs and transactional service boundaries**

Use both annotations on every admin method:

```java
@SaCheckRole("GLOBAL_SYSTEM_ADMIN")
@SaCheckPermission("platform-ssl:view")
```

For writes use `platform-ssl:update`. Add `/api/admin/platform-ssl/**` to `TenantInterceptor.isTenantNeutral`; do not add it to authentication exclusions. Map `PlatformSslException` to 400 and `PlatformSslConflictException` to 409 with existing `{code,message}` shape.

The config DTO contains `platformDomain`, `externalHttpsUrl`, and `configVersion`. Validate lowercase IDNA domain, absolute HTTPS URL, matching host, no userinfo/query/fragment, and max lengths. A disabled config may have no certificate. Enabling validates/decrypts the active certificate again, inserts a PENDING operation, updates target state with optimistic version, and schedules the coordinator.

Manual upload uses `@RequestPart("fullchain") MultipartFile` and `@RequestPart("privateKey") MultipartFile`. Insert encrypted candidate first. If runtime is HTTP, mark it READY and select it for the next enable; if runtime is HTTPS, hot reload first and only then atomically make it ACTIVE. On reload or final DB activation failure, restore the old runtime certificate, mark candidate FAILED, and preserve the old active ID.

Reconnect URL rules are deterministic: enabling returns configured `externalHttpsUrl`; disabling preserves host and explicit HTTPS port, and if the HTTPS URL omits a port uses `http://host:443` so Docker `443:9860` remains reachable as HTTP during an intentional disable.

- [ ] **Step 4: Run GREEN tests and compile**

Run: `mvn -Dtest=PlatformSslAdminControllerTest test && mvn compile`

Expected: PASS; response JSON contains metadata only, 202/409/403 semantics match the contract, and no view/controller catch logs secret bytes.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/top/hetao/shiyuanticketmp/platformssl src/main/java/top/hetao/shiyuanticketmp/common/exception/GlobalExceptionHandler.java src/main/java/top/hetao/shiyuanticketmp/common/config/SaTokenConfig.java src/test/java/top/hetao/shiyuanticketmp/platformssl/web/PlatformSslAdminControllerTest.java
git commit -m "feat: add platform ssl administration api"
```

### Task 7: Implement scoped ACME deploy tokens and idempotent renewal import

**Files:**
- Create: `src/main/java/top/hetao/shiyuanticketmp/platformssl/acme/PlatformSslDeployToken.java`
- Create: `src/main/java/top/hetao/shiyuanticketmp/platformssl/acme/PlatformSslDeployTokenMapper.java`
- Create: `src/main/java/top/hetao/shiyuanticketmp/platformssl/acme/PlatformSslDeployTokenService.java`
- Create: `src/main/java/top/hetao/shiyuanticketmp/platformssl/acme/TrustedNetworkPolicy.java`
- Create: `src/main/java/top/hetao/shiyuanticketmp/platformssl/web/PlatformSslDeployController.java`
- Modify: `src/main/java/top/hetao/shiyuanticketmp/platformssl/web/PlatformSslAdminController.java`
- Modify: `src/main/java/top/hetao/shiyuanticketmp/common/config/SaTokenConfig.java`
- Test: `src/test/java/top/hetao/shiyuanticketmp/platformssl/acme/PlatformSslDeployTokenServiceTest.java`
- Test: `src/test/java/top/hetao/shiyuanticketmp/platformssl/web/PlatformSslDeployControllerTest.java`

- [ ] **Step 1: Write RED token/deploy tests**

Tests must cover 256-bit random secret returned once as `<uuid>.<base64url-secret>`, SHA-256 hash only in DB, scope exactly `CERTIFICATE_DEPLOY`, expiry 1–90 days, revocation, constant-time comparison, malformed/expired/revoked tokens as 401, wrong scope as 403, same fingerprint as idempotent 200 without reload, new fingerprint as reload-and-activate, and response/log redaction.

Also prove an insecure HTTP deploy accepts only `request.getRemoteAddr()` in `bootstrap-trusted-cidrs`; default loopback passes, public/private/Docker addresses fail unless explicitly listed. Never trust `X-Forwarded-For` because no proxy layer exists.

- [ ] **Step 2: Run RED tests**

Run: `mvn -Dtest=PlatformSslDeployTokenServiceTest,PlatformSslDeployControllerTest test`

Expected: FAIL because ACME token and deploy endpoints do not exist.

- [ ] **Step 3: Implement one-time token display and deploy endpoint**

Admin endpoints:

```text
GET    /api/admin/platform-ssl/deploy-tokens
POST   /api/admin/platform-ssl/deploy-tokens   { expiresInDays: 1..90 }
DELETE /api/admin/platform-ssl/deploy-tokens/{tokenId}
```

Deploy endpoint:

```text
POST /api/platform-ssl/deploy
Authorization: Bearer <uuid>.<secret>
multipart: fullchain, privateKey
```

Add only `/api/platform-ssl/deploy` to both Sa login and tenant exclusions. The controller itself authenticates the scoped token before reading multipart bytes. If `request.isSecure()` is false, enforce `TrustedNetworkPolicy` against the socket remote address. Reuse the exact parser/encryption/activation path from Task 6; do not duplicate certificate rules. Identical fingerprint returns `{replayed:true}` and does not call `reloadSslHostConfig`.

- [ ] **Step 4: Run GREEN tests**

Run: `mvn -Dtest=PlatformSslDeployTokenServiceTest,PlatformSslDeployControllerTest test`

Expected: PASS; raw token appears only in the create response and never in list/database/log assertions.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/top/hetao/shiyuanticketmp/platformssl/acme src/main/java/top/hetao/shiyuanticketmp/platformssl/web src/main/java/top/hetao/shiyuanticketmp/common/config/SaTokenConfig.java src/test/java/top/hetao/shiyuanticketmp/platformssl/acme src/test/java/top/hetao/shiyuanticketmp/platformssl/web/PlatformSslDeployControllerTest.java
git commit -m "feat: accept scoped acme certificate deploys"
```

### Task 8: Add an explicit one-time legacy SSL import command

**Files:**
- Create: `src/main/java/top/hetao/shiyuanticketmp/platformssl/migration/LegacyPlatformSslImportProperties.java`
- Create: `src/main/java/top/hetao/shiyuanticketmp/platformssl/migration/LegacyPlatformSslImporter.java`
- Create: `src/main/java/top/hetao/shiyuanticketmp/platformssl/migration/LegacyPlatformSslImportRunner.java`
- Test: `src/test/java/top/hetao/shiyuanticketmp/platformssl/migration/LegacyPlatformSslImporterTest.java`

- [ ] **Step 1: Write RED migration tests**

```java
@Test
void importsLegacyStoreOnceIntoGlobalTables() {
    importer.importStore(propertiesFor(validPkcs12));
    verify(certificateMapper).insert(argThat(row -> row.getFingerprintSha256().equals(FINGERPRINT)));
    verify(configMapper).completeLegacyImport(eq(FINGERPRINT), any(Instant.class), eq(true));
    verifyNoInteractions(tenantService);
}

@Test
void wrongSanOrExistingConfigurationLeavesDatabaseUntouched() {
    assertThatThrownBy(() -> importer.importStore(propertiesFor(wrongSanPkcs12)))
        .isInstanceOf(PlatformSslException.class);
    verifyNoInteractions(certificateMapper);
    when(configMapper.hasUserConfiguration()).thenReturn(true);
    assertThatThrownBy(() -> importer.importStore(propertiesFor(validPkcs12)))
        .isInstanceOf(PlatformSslConflictException.class);
}

@Test
void identicalCompletedImportIsIdempotentAndAuditIsRedacted() {
    when(configMapper.legacyFingerprint()).thenReturn(FINGERPRINT);
    LegacyImportResult result = importer.importStore(propertiesFor(validPkcs12));
    assertThat(result.replayed()).isTrue();
    assertThat(result.auditSummary()).contains(FINGERPRINT)
        .doesNotContain("password", "PRIVATE KEY", Base64.getEncoder().encodeToString(pkcs12Bytes));
    verify(certificateMapper, never()).insert(any());
}
```

- [ ] **Step 2: Run RED test**

Run: `mvn -Dtest=LegacyPlatformSslImporterTest test`

Expected: FAIL because importer does not exist.

- [ ] **Step 3: Implement an opt-in non-web import path**

Bind only explicit `platform.ssl.legacy-import.*` values: `enabled`, `key-store`, `key-store-password`, `key-alias`, `key-password`, `platform-domain`, `external-https-url`, `enable-after-import`. Do not reuse `server.ssl.*`, because Boot must never independently configure TLS.

The conditional `ApplicationRunner` executes after Flyway in a non-web process, loads JKS/PKCS12 through `ResourceLoader`, extracts private key and chain in memory, passes them through the same validation/encryption service, writes the global singleton/certificate and `legacy_imported_at` in one transaction, then closes the application context. It refuses to run if platform config/certificates already exist except an identical completed import.

Documented production command shape for the later deployment task:

```bash
java -jar shiyuan-ticket-MP.jar \
  --spring.main.web-application-type=none \
  --platform.ssl.legacy-import.enabled=true \
  --platform.ssl.legacy-import.key-store=/run/secrets/legacy.p12 \
  --platform.ssl.legacy-import.platform-domain=ticket.example.com \
  --platform.ssl.legacy-import.external-https-url=https://ticket.example.com
```

Passwords/master key come only from environment or secret files and must not appear in command history examples.

- [ ] **Step 4: Run GREEN test**

Run: `mvn -Dtest=LegacyPlatformSslImporterTest test`

Expected: PASS; import never writes tenant 100 and never overwrites existing global configuration.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/top/hetao/shiyuanticketmp/platformssl/migration src/test/java/top/hetao/shiyuanticketmp/platformssl/migration
git commit -m "feat: migrate legacy ssl into platform storage"
```

### Task 9: Build the global-admin platform SSL page

**Files:**
- Create: `../front/src/api/admin/platform-ssl.js`
- Create: `../front/src/utils/platform-ssl.js`
- Create: `../front/src/views/platform/ssl.vue`
- Create: `../front/tests/platform-ssl.test.mjs`
- Modify: `../front/src/router/index.js`
- Modify: `../front/src/layout/index.vue`

- [ ] **Step 1: Write RED Node contract tests**

```js
import test from 'node:test'
import assert from 'node:assert/strict'
import { normalizePlatformSslStatus, buildReconnectUrl } from '../src/utils/platform-ssl.js'

test('normalizer rejects secret-bearing or incomplete responses', () => {
  assert.throws(() => normalizePlatformSslStatus({ privateKey: 'secret' }))
})

test('disable reconnect preserves the externally mapped TLS port', () => {
  assert.equal(buildReconnectUrl('https://ticket.example.com', false), 'http://ticket.example.com:443')
  assert.equal(buildReconnectUrl('https://ticket.example.com:9860', false), 'http://ticket.example.com:9860')
})
```

- [ ] **Step 2: Run RED frontend test**

Run from `front/`: `node --test tests/platform-ssl.test.mjs`

Expected: FAIL because helper/API/page do not exist.

- [ ] **Step 3: Implement API module, tenant-neutral route, and guarded navigation**

All API paths use the existing request instance and omit `/api`:

```js
export const getPlatformSsl = () => request({ url: '/admin/platform-ssl', method: 'get' })
export const uploadPlatformCertificate = formData => request({
  url: '/admin/platform-ssl/certificates', method: 'post', data: formData,
})
export const requestProtocolSwitch = data => request({
  url: '/admin/platform-ssl/protocol-switch', method: 'post', data,
})
```

Add static route `/platform/ssl` with `meta: { title: '平台 SSL', permission: 'platform-ssl:view', globalAdmin: true, tenantNeutral: true }`. Update both initial and steady-state router guards so global admins without `activeTenantId` may reach routes with `tenantNeutral`, while tenant routes still redirect to `/system/tenant`. Add a “平台 SSL” dropdown item only when `userStore.globalAdmin` and permission is present.

- [ ] **Step 4: Implement page states and safety prompts**

The page contains:

- runtime/target protocol, domain, external URL, active fingerprint/SAN/expiry/load status;
- editable domain and HTTPS URL with optimistic `configVersion`;
- optional PEM fullchain/private-key file selectors (never bind content into reactive/localStorage state longer than upload);
- certificate version metadata table with no secret columns;
- enable/disable confirmation explicitly stating a short disconnect and that same-port HTTP cannot redirect while TLS is active;
- accepted switch countdown followed by `window.location.assign(reconnectUrl + '?sslOperation=' + operationId)`;
- deploy-token list/create/revoke, with raw token shown once in a non-persistent dialog and a copy button;
- DNS-01 recommended instructions and host standalone HTTP-01 warning.

Views catch request failures only with `console.error`; `request.js` remains the only error toast source. On save/upload failure restore the last normalized snapshot. Do not acquire a tenant context operation because this route is platform-global.

- [ ] **Step 5: Run frontend verification**

Run from `front/`:

```bash
node --test tests/platform-ssl.test.mjs
npm run build
```

Expected: Node tests PASS and Vite build succeeds. Manual role check: tenant `SYSTEM_ADMIN` cannot see/navigate the page; `GLOBAL_SYSTEM_ADMIN` can access it without selecting a tenant.

- [ ] **Step 6: Commit the frontend repository**

```bash
git add src/api/admin/platform-ssl.js src/utils/platform-ssl.js src/views/platform/ssl.vue src/router/index.js src/layout/index.vue tests/platform-ssl.test.mjs
git commit -m "feat: add platform ssl management page"
```

### Task 10: Add Docker/acme.sh deployment assets, real handshake verification, and API documentation

**Files:**
- Create: `deploy/platform-ssl/compose.yml`
- Create: `deploy/platform-ssl/acme-deploy.sh`
- Create: `docs/deployment/platform-ssl.md`
- Create: `src/test/java/top/hetao/shiyuanticketmp/platformssl/PlatformSslSecurityRegressionTest.java`
- Modify: Apifox project `8260787` (external documentation; no local `API.md`)

- [ ] **Step 1: Write a RED repository regression test**

```java
@Test
void deploymentKeepsOneContainerPortAndNoProxyLayer() throws Exception {
    String compose = Files.readString(Path.of("deploy/platform-ssl/compose.yml"));
    assertThat(compose).contains("443:9860");
    assertThat(compose).doesNotContain("80:9860", "nginx", "caddy", "haproxy");
    String docs = Files.readString(Path.of("docs/deployment/platform-ssl.md"));
    assertThat(docs).contains("DNS-01", "--standalone", "reloadSslHostConfig", "http://host:9860 不会返回 308");
}
```

- [ ] **Step 2: Run RED test**

Run: `mvn -Dtest=PlatformSslSecurityRegressionTest test`

Expected: FAIL because deployment assets do not exist.

- [ ] **Step 3: Add the one-port Compose example and deploy hook**

`deploy/platform-ssl/compose.yml` publishes exactly:

```yaml
services:
  ticket-api:
    image: shiyuan-ticket-mp:1.0.7
    ports:
      - "443:9860"
    environment:
      PLATFORM_SECRET_KEY_V1: ${PLATFORM_SECRET_KEY_V1:?inject a Base64 32-byte key at runtime}
```

The deployment system must inject `PLATFORM_SECRET_KEY_V1` from its secret store; never commit the Base64 value to Compose or `.env`. Do not expose another container port. The documented first HTTP bootstrap alternative is a temporary replacement mapping `127.0.0.1:9860:9860`, not a second Connector or simultaneous second production mapping.

`acme-deploy.sh` must use only explicit env/file inputs and `curl --fail-with-body`:

```sh
#!/bin/sh
set -eu
: "${PLATFORM_SSL_DEPLOY_URL:?required}"
: "${PLATFORM_SSL_DEPLOY_TOKEN_FILE:?required}"
: "${PLATFORM_SSL_FULLCHAIN:?required}"
: "${PLATFORM_SSL_PRIVATE_KEY:?required}"
token="$(tr -d '\r\n' < "$PLATFORM_SSL_DEPLOY_TOKEN_FILE")"
curl --fail-with-body --silent --show-error \
  -H "Authorization: Bearer $token" \
  -F "fullchain=@$PLATFORM_SSL_FULLCHAIN;type=application/x-pem-file" \
  -F "privateKey=@$PLATFORM_SSL_PRIVATE_KEY;type=application/x-pem-file" \
  "$PLATFORM_SSL_DEPLOY_URL/api/platform-ssl/deploy"
```

Document `acme.sh --issue --dns dns_<provider>` as the normal renewal path, followed by stable `--install-cert` paths and `--reloadcmd` invoking this script. DNS provider credentials remain owned by acme.sh. Document host `acme.sh --standalone` on public port 80 for HTTP-01; never switch the live HTTPS application back to HTTP for unattended renewals and never run shell from Java.

- [ ] **Step 4: Execute focused and full build gates**

Run from `backend/`:

```bash
mvn -Dtest='PlatformSsl*Test,Tomcat*Test,LegacyPlatformSslImporterTest' test
mvn compile
```

Run from `front/`:

```bash
node --test tests/platform-ssl.test.mjs
npm run build
```

Expected: all focused pure tests PASS; both projects compile/build. If dev MySQL/Redis are available, also run `mvn test`; otherwise report the external-service test gate as not run rather than claiming success.

- [ ] **Step 5: Perform real Docker/TLS acceptance**

With a disposable MySQL/Redis and test domain/certificate:

1. Disabled DB state: `curl -v http://127.0.0.1:9860/api/system/version` succeeds through a loopback mapping.
2. Enabled DB state and `443:9860`: `openssl s_client -connect 127.0.0.1:443 -servername <domain> -showcerts` reports the active SHA-256 fingerprint on the first listener; no HTTP-first window appears.
3. `curl -v http://127.0.0.1:443/` fails at protocol parsing and does not receive 308.
4. Import a second RSA or ECC certificate through the deploy hook; a new `openssl s_client` connection sees the new fingerprint while Spring PID/context and Connector identity remain unchanged.
5. Inject a reload failure; old fingerprint remains active and DB active ID is unchanged.
6. Inject a replacement-bind failure during HTTP/HTTPS switching; old protocol is reachable again, target state is rolled back, and operation is FAILED with a redacted summary.
7. Restart with enabled-but-corrupt ciphertext, missing certificate, expired certificate, and wrong SAN; every case exits non-zero without accepting HTTP.

- [ ] **Step 6: Sync Apifox and commit deployment assets**

Update Apifox project `8260787` with all Task 6/7 endpoints, multipart fields, 202/400/401/403/409 responses, metadata-only schemas, and one-time token semantics. Do not create `API.md`.

```bash
git add deploy/platform-ssl docs/deployment/platform-ssl.md src/test/java/top/hetao/shiyuanticketmp/platformssl/PlatformSslSecurityRegressionTest.java
git commit -m "docs: add single-port ssl deployment runbook"
```

## Completion audit

- Optional SSL: absent tables, absent row, or explicit disabled starts HTTP; no certificate is required.
- Fail closed: enabled invalid states never downgrade to HTTP, including cold restart and decryption failure.
- Single port: no second application Connector, no container 80/443 listener, no Nginx/Caddy/HAProxy; Docker maps only host 443 to container 9860.
- Runtime safety: protocol switch is serialized, drains in-flight work, restores old Connector and target on failure; renewal reload preserves old certificate on failure.
- Key safety: RSA/ECC/chain/key/SAN/expiry validation, encrypted PKCS12, external master key, redacted API/log/audit, hashed scoped deploy tokens.
- ACME: DNS-01 default, host standalone HTTP-01 only, deploy hook posts stable fullchain/key paths, no Java shell execution.
- Platform authorization: global role plus platform permission; tenant admins and unauthenticated callers denied; platform route works without active tenant.
- Migration: legacy SSL imports into global platform records, never tenant 100, is explicit and idempotent, and enables first-bind TLS after an offline import.
- Verification: focused tests, backend compile, frontend Node test/build, real `443:9860` handshake, no-308 assertion, reload and rollback fault injection, and Apifox sync are all recorded with actual output before completion is claimed.
