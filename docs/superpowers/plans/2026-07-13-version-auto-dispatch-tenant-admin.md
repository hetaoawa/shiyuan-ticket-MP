# Version, Automation, and Tenant Administration Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (- [ ]) syntax for tracking.

**Goal:** Deliver independent frontend/backend version identification, Flyway migrations, durable one-minute external-work-order assignment, per-tenant integration switches, strict tenant isolation, tenant/global administrators, and the confirmed bug fixes.

**Architecture:** Introduce sys_tenant and sys_tenant_setting as authoritative data, separate GLOBAL_SYSTEM_ADMIN from tenant SYSTEM_ADMIN, and make activeTenantId the only HTTP business scope. Cross-tenant work is limited to explicit internal scopes. External side effects carry tenant context and run after commit. Frontend and backend remain independently versioned and deployed.

**Tech Stack:** Java 17, Spring Boot 3.0.2, Flyway 9.5.1 (Boot-managed), MyBatis-Plus 3.5.5, Sa-Token 1.38.0, MySQL, Redis, Vue 3, Vite 8, Pinia, Element Plus.

---

## Global execution rules

- Work only on feat/version-auto-dispatch-tenant-admin in both repositories.
- Read backend/AGENTS.md or front/AGENTS.md before editing the corresponding repository.
- Never stage existing unrelated untracked files such as environment files, local reports, screenshots, or opencode configuration.
- For behavior changes, create a temporary focused test first, run it and confirm the expected failure, implement the behavior, run it and confirm success, then delete the newly created test before staging.
- Do not commit any newly created test source, test script, fixture, screenshot, report, coverage output, upload sample, target directory, or dist directory.
- The existing tracked ShiyuanTicketMpApplicationTests.java remains untouched.
- Each task makes at most one signed functional commit per affected repository. Use the configured key 1B7FF0413D02217B and email 79517899+hetaoawa@users.noreply.github.com.
- After each commit, verify git log -1 --show-signature and confirm a good signature, the required email, and no temporary test file in git show --name-only.
- API modules use paths relative to the frontend /api base URL.
- Long IDs remain JSON/frontend strings.

### Task 1: Enable Flyway and ignore generated artifacts

**Problem group:** Additional Flyway and repository-hygiene requirement.

**Files:**
- Modify: backend/pom.xml
- Modify: backend/src/main/resources/application.properties
- Modify: backend/.gitignore
- Track: backend/src/main/resources/db/migration/V1__create_work_order.sql through V16__add_assignee_role.sql
- Rename: backend/src/main/resources/db/migration/V10__add_cargo_owner_fields.sql to backend/src/main/resources/db/migration/V10_1__add_cargo_owner_fields.sql
- Create: backend/src/main/resources/db/bootstrap/enable_flyway_existing_production.sql
- Modify: front/.gitignore
- Temporary test: backend/src/test/java/top/hetao/shiyuanticketmp/FlywayMigrationInventoryTest.java

- [ ] **Step 1: Add a temporary failing migration-inventory test**

Create FlywayMigrationInventoryTest.java that constructs Flyway with a no-connect mock DataSource only far enough to use the classpath scanner, or directly scans classpath resources and asserts the exact ordered versions:

~~~java
@Test
void migrationVersionsAreUniqueAndLegacyChainEndsAtSixteen() throws Exception {
    List<String> names = Files.list(Path.of("src/main/resources/db/migration"))
            .map(path -> path.getFileName().toString())
            .filter(name -> name.startsWith("V"))
            .sorted()
            .toList();
    assertThat(names).contains("V10__add_resubmit_force_reject_permissions.sql");
    assertThat(names).contains("V10_1__add_cargo_owner_fields.sql");
    assertThat(names.stream().filter(name -> name.startsWith("V10__")).count()).isEqualTo(1);
    assertThat(names).contains("V16__add_assignee_role.sql");
}
~~~

Run from backend:

    mvn -Dtest=FlywayMigrationInventoryTest test

Expected RED: V10_1 is missing and two V10 files exist.

- [ ] **Step 2: Add Flyway dependencies and fail-closed configuration**

In pom.xml add Boot-managed dependencies without explicit versions:

~~~xml
<dependency>
    <groupId>org.flywaydb</groupId>
    <artifactId>flyway-core</artifactId>
</dependency>
<dependency>
    <groupId>org.flywaydb</groupId>
    <artifactId>flyway-mysql</artifactId>
</dependency>
~~~

In application.properties add:

~~~properties
spring.flyway.enabled=true
spring.flyway.locations=classpath:db/migration
spring.flyway.validate-on-migrate=true
spring.flyway.out-of-order=false
spring.flyway.baseline-on-migrate=false
spring.flyway.clean-disabled=true
~~~

- [ ] **Step 3: Normalize and track the legacy chain**

Rename only the cargo-owner V10 script to V10_1__add_cargo_owner_fields.sql. Do not edit the SQL body. Track all legacy migration scripts V1 through V16 so empty databases have a complete Flyway chain.

- [ ] **Step 4: Add the production baseline bootstrap SQL**

Create enable_flyway_existing_production.sql outside db/migration. It must use the Flyway 9.5.1 MySQL schema-history layout:

~~~sql
DROP PROCEDURE IF EXISTS bootstrap_flyway_v16;
DELIMITER //
CREATE PROCEDURE bootstrap_flyway_v16()
BEGIN
    IF EXISTS (
        SELECT 1
        FROM information_schema.tables
        WHERE table_schema = DATABASE()
          AND table_name = 'flyway_schema_history'
    ) THEN
        SIGNAL SQLSTATE '45000'
            SET MESSAGE_TEXT = 'flyway_schema_history already exists; baseline aborted';
    END IF;

    CREATE TABLE flyway_schema_history (
        installed_rank INT NOT NULL,
        version VARCHAR(50),
        description VARCHAR(200) NOT NULL,
        type VARCHAR(20) NOT NULL,
        script VARCHAR(1000) NOT NULL,
        checksum INT,
        installed_by VARCHAR(100) NOT NULL,
        installed_on TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
        execution_time INT NOT NULL,
        success TINYINT(1) NOT NULL,
        CONSTRAINT flyway_schema_history_pk PRIMARY KEY (installed_rank)
    ) ENGINE=InnoDB;

    CREATE INDEX flyway_schema_history_s_idx
        ON flyway_schema_history (success);

    INSERT INTO flyway_schema_history
        (installed_rank, version, description, type, script, checksum,
         installed_by, execution_time, success)
    VALUES
        (1, '16', 'Existing production baseline', 'BASELINE',
         '<< Flyway Baseline >>', NULL, CURRENT_USER(), 0, 1);
END//
DELIMITER ;
CALL bootstrap_flyway_v16();
DROP PROCEDURE bootstrap_flyway_v16;
~~~

Add comments requiring backup and verification that V1-V16 changes already exist. Validate the exact DDL against the resolved Flyway 9.5.1 dependency before commit.

- [ ] **Step 5: Expand ignore rules**

Backend .gitignore must include Maven/Flyway/test outputs and local artifacts:

~~~gitignore
/target/
/playwright-report/
/test-results/
/screenshots/
/*test-result*.json
/test-upload*
*.log
~~~

Frontend .gitignore must retain node_modules, dist, dist-ssr, coverage and add:

~~~gitignore
/playwright-report/
/test-results/
/screenshots/
/*test-result*.json
/integration-test-result*.json
/test-upload*
~~~

Do not delete existing user artifacts.

- [ ] **Step 6: Verify GREEN and remove the temporary test**

Run:

    mvn -Dtest=FlywayMigrationInventoryTest test
    mvn compile
    npm run build

Expected: temporary test passes, backend compile succeeds, frontend build succeeds. Delete FlywayMigrationInventoryTest.java, then confirm git status contains no new test file or build output.

- [ ] **Step 7: Commit both repositories**

Backend stage only pom.xml, application.properties, .gitignore, db/migration, and db/bootstrap:

    git commit -S1B7FF0413D02217B -m "build: enable flyway migrations"

Frontend stage only .gitignore:

    git commit -S1B7FF0413D02217B -m "chore: ignore generated test artifacts"

### Task 2: Establish tenant master data and strict tenant isolation

**Problem group:** 4 — tenant logic, tenant display, and isolation defects.

**Files:**
- Create: backend/src/main/java/top/hetao/shiyuanticketmp/tenant/entity/SysTenant.java
- Create: backend/src/main/java/top/hetao/shiyuanticketmp/tenant/mapper/SysTenantMapper.java
- Create: backend/src/main/java/top/hetao/shiyuanticketmp/tenant/service/TenantService.java
- Create: backend/src/main/java/top/hetao/shiyuanticketmp/tenant/controller/TenantController.java
- Create: backend/src/main/java/top/hetao/shiyuanticketmp/tenant/controller/dto/TenantRequest.java
- Create: backend/src/main/resources/db/migration/V17__create_sys_tenant.sql
- Modify: backend/src/main/java/top/hetao/shiyuanticketmp/common/context/TenantContext.java
- Modify: backend/src/main/java/top/hetao/shiyuanticketmp/common/config/MybatisPlusConfig.java
- Modify: backend/src/main/java/top/hetao/shiyuanticketmp/workorder/cache/WorkOrderCacheManager.java
- Modify: backend/src/main/java/top/hetao/shiyuanticketmp/workorder/service/impl/WorkOrderServiceImpl.java
- Modify: backend/src/main/java/top/hetao/shiyuanticketmp/workorder/controller/WorkOrderController.java
- Modify: backend/src/main/java/top/hetao/shiyuanticketmp/workorder/entity/WorkOrder.java
- Modify: backend/src/main/java/top/hetao/shiyuanticketmp/workorder/comment/service/WorkOrderCommentService.java
- Modify: backend/src/main/java/top/hetao/shiyuanticketmp/audit/listener/WorkOrderAuditListener.java
- Modify: backend/src/main/java/top/hetao/shiyuanticketmp/common/config/MybatisPlusConfig.java
- Modify: front/src/api/admin/tenant.js
- Create: front/src/utils/tenant.js
- Modify: front/src/views/admin/users.vue
- Modify: front/src/views/workorder/list.vue
- Modify: front/src/views/workorder/detail.vue
- Temporary tests: backend TenantContextTest.java, WorkOrderTenantIsolationTest.java; front temp-tenant-contract.test.mjs

- [ ] **Step 1: Write temporary failing tenant-scope tests**

TenantContextTest must assert:

~~~java
assertThatThrownBy(TenantContext::requireTenantId)
        .isInstanceOf(IllegalStateException.class);
try (TenantContext.Scope ignored = TenantContext.useTenant(100L)) {
    assertThat(TenantContext.requireTenantId()).isEqualTo(100L);
}
assertThat(TenantContext.getTenantId()).isNull();
~~~

WorkOrderTenantIsolationTest must assert that a cached order from tenant 100 is rejected in tenant 200 and that cache keys differ by tenant. Run the focused tests and confirm RED because requireTenantId/scope APIs and tenant-aware cache keys do not exist.

- [ ] **Step 2: Create authoritative tenant data**

V17 creates sys_tenant as a global table with id, tenant_code, tenant_name, status, created_at, updated_at, deleted, unique tenant_code, and status index. Insert platform tenant 0 and backfill distinct tenant IDs found in sys_user, sys_role, work_order, sys_file, sys_menu, webhook_dead_letter, sys_audit_log, and work_order_comment. Generated codes are platform for 0 and tenant-{id} for existing business tenants.

SysTenant maps the table without inheriting tenant filtering. TenantService provides:

~~~java
SysTenant requireEnabled(Long tenantId);
List<SysTenant> listEnabled();
String getTenantName(Long tenantId);
SysTenant createTenant(TenantRequest request);
SysTenant updateTenant(Long id, TenantRequest request);
~~~

TenantController exposes GET /api/admin/tenants/options for authenticated users and global-only CRUD endpoints under /api/admin/tenants.

- [ ] **Step 3: Replace boolean admin bypass with scoped context**

TenantContext exposes getTenantId, requireTenantId, useTenant, useInternalBypass, and isInternalBypass. Scope implements AutoCloseable and restores the previous state on close. Remove role-derived setAdmin/isAdmin.

MybatisPlusConfig ignores tenant filtering only when isInternalBypass is true or the table is genuinely global: sys_tenant, sys_permission, sys_user_role, sys_role_permission, sys_config, sys_dict, and express_trace. Remove sys_audit_log and work_order_comment from exclusions. Missing normal tenant context must throw instead of injecting 0.

- [ ] **Step 4: Make work-order access and caching tenant-safe**

WorkOrderCacheManager keys use workorder:{tenantId}:{workOrderId}. WorkOrderServiceImpl verifies order.tenantId equals TenantContext.requireTenantId before role/submitter/assignee checks, including cache hits. Create/update operations explicitly set tenantId. Assignee user/role lookups must stay in the active tenant.

Add tenantName as a non-persistent response field on WorkOrder and populate it in list/detail responses through TenantService without converting IDs.

- [ ] **Step 5: Fix tenant inheritance for comments and audit**

WorkOrderCommentService loads the work order through the access-checked service before insert, copies its tenantId, and lists comments inside the active tenant.

WorkOrderAuditListener copies tenantId from the event order before save. Audit queries retain tenant filtering.

Document express_trace as a global carrier cache; keep its legacy tenant value 0 and exclusion.

- [ ] **Step 6: Update frontend tenant sources and display**

tenant.js uses /admin/tenants/options. Remove allow-create from the users page tenant selector. Keep tenant IDs as strings. Show tenantName plus tenantId in work-order list/detail when supplied.

tenant.js exports formatTenantLabel(tenantName, tenantId), returning the display name plus the original string ID without Number conversion. The temporary Node test imports this helper and asserts that "9007199254740993" remains unchanged. Run it RED before creating the helper, GREEN after, then delete temp-tenant-contract.test.mjs.

- [ ] **Step 7: Verify, delete temporary tests, and commit**

Run focused backend tests, mvn compile, and npm run build. Delete all newly created tests. Confirm no test/build artifact is staged.

Backend commit:

    git commit -S1B7FF0413D02217B -m "fix: enforce tenant isolation and names"

Frontend commit:

    git commit -S1B7FF0413D02217B -m "fix: display authoritative tenant data"

### Task 3: Add tenant administrators and global tenant switching

**Problem group:** 5 — tenant system administrators and global administrator switching.

**Files:**
- Create: backend/src/main/resources/db/migration/V18__split_global_and_tenant_admin_roles.sql
- Create: backend/src/main/java/top/hetao/shiyuanticketmp/auth/controller/dto/SwitchTenantRequest.java
- Create: backend/src/main/java/top/hetao/shiyuanticketmp/auth/service/AuthTenantService.java
- Create: backend/src/main/java/top/hetao/shiyuanticketmp/auth/service/AuthTenantContext.java
- Create: backend/src/main/java/top/hetao/shiyuanticketmp/auth/service/RoleProvisioningService.java
- Create: backend/src/main/java/top/hetao/shiyuanticketmp/tenant/controller/dto/AssignTenantAdminsRequest.java
- Modify: backend/src/main/java/top/hetao/shiyuanticketmp/auth/AuthController.java
- Modify: backend/src/main/java/top/hetao/shiyuanticketmp/common/config/SaTokenConfig.java
- Modify: backend/src/main/java/top/hetao/shiyuanticketmp/auth/service/UserService.java
- Modify: backend/src/main/java/top/hetao/shiyuanticketmp/auth/service/RoleService.java
- Modify: backend/src/main/java/top/hetao/shiyuanticketmp/auth/mapper/SysUserMapper.java
- Modify: backend/src/main/java/top/hetao/shiyuanticketmp/auth/mapper/SysRoleMapper.java
- Modify: backend/src/main/java/top/hetao/shiyuanticketmp/auth/mapper/SysPermissionMapper.java
- Modify: backend/src/main/java/top/hetao/shiyuanticketmp/tenant/controller/TenantController.java
- Modify: backend/src/main/java/top/hetao/shiyuanticketmp/tenant/service/TenantService.java
- Modify: front/src/views/login/index.vue
- Modify: front/src/stores/user.js
- Modify: front/src/api/auth.js
- Create: front/src/utils/auth.js
- Modify: front/src/layout/index.vue
- Modify: front/src/views/admin/users.vue
- Temporary tests: backend AuthTenantSwitchTest.java, UserRoleTenantBoundaryTest.java; front temp-auth-state.test.mjs

- [ ] **Step 1: Write failing role and switch tests**

Tests construct AuthTenantService and must prove:

~~~java
assertThat(authTenantService.resolveLogin("tenant-100", "alice").getTenantId()).isEqualTo(100L);
assertThatThrownBy(() -> authTenantService.switchTenant(nonGlobalUser, 200L))
        .isInstanceOf(AccessDeniedException.class);
assertThat(authTenantService.switchTenant(globalUser, 200L).activeTenantId()).isEqualTo(200L);
assertThatThrownBy(() -> userService.assignRoles(tenant100User, List.of(globalRoleId)))
        .isInstanceOf(AccessDeniedException.class);
~~~

Confirm RED against current username-only login, absent switch API, and unchecked role bindings.

- [ ] **Step 2: Migrate role semantics**

V18 renames tenant-0 SYSTEM_ADMIN to GLOBAL_SYSTEM_ADMIN, provisions SYSTEM_ADMIN inside every enabled business tenant, copies the intended administrator permissions, and leaves WAREHOUSE_ADMIN/CARGO_OWNER tenant-scoped. It prevents duplicate role codes within a tenant.

- [ ] **Step 3: Implement tenant-code login and session state**

AuthTenantContext is an immutable value with principalTenantId, activeTenantId, activeTenantName, and globalAdmin. AuthTenantService resolves login identity, validates switches, and writes/reads these session attributes. Login accepts tenantCode, username, password as form-urlencoded fields to preserve the current frontend request mechanism. SysUserMapper resolves by joined sys_tenant. On login store principalTenantId, activeTenantId for tenant users or null for global users, and globalAdmin.

/auth/me returns principalTenantId, activeTenantId, activeTenantName, globalAdmin, roles, permissions, and existing profile fields.

POST /api/auth/switch-tenant accepts:

~~~java
public class SwitchTenantRequest {
    private Long tenantId;
}
~~~

Only GLOBAL_SYSTEM_ADMIN can switch. TenantService.requireEnabled validates the target. Update activeTenantId in the session and return the updated context.

SaTokenConfig TenantInterceptor reads activeTenantId into TenantContext for each request and always clears it. It must not derive tenant bypass from a role.

- [ ] **Step 4: Enforce administration boundaries**

UserService create/update accepts a different tenant only for a global administrator already switched to that tenant; tenant administrators cannot move users. assignRoles loads every role under internal scope, verifies role.tenantId equals user.tenantId, and rejects GLOBAL_SYSTEM_ADMIN for non-platform users.

Permission queries join sys_user_role and sys_role and enforce consistent ownership rather than trusting global relation tables.

RoleProvisioningService creates SYSTEM_ADMIN, WAREHOUSE_ADMIN, and CARGO_OWNER roles with the standard permission templates whenever TenantService creates a tenant. TenantService assigns system administrators transactionally using the tenant-local SYSTEM_ADMIN role. TenantController exposes GET/PUT /api/admin/tenants/{tenantId}/system-admins; global administrators may manage the active tenant and tenant SYSTEM_ADMIN may manage only its own tenant without granting global roles.

- [ ] **Step 5: Update frontend login and tenant switcher**

Add tenantCode to the login form and remembered state; remember only tenantCode and username.

auth.js exports normalizeAuthContext(response), preserving every tenant ID as a string. Pinia state contains principalTenantId, activeTenantId, activeTenantName, globalAdmin, and availableTenants. switchTenant calls /auth/switch-tenant, then reloads /auth/me and menus and resets tenant-specific view state. The temporary Node test imports normalizeAuthContext and verifies global/tenant contexts before the store is changed.

Layout shows active tenant and shows the tenant selector only for globalAdmin. After switching, replace the current route or reload it so stale records cannot remain.

Users page allows tenant selection only for globalAdmin and uses backend-returned assignable roles.

- [ ] **Step 6: Verify and commit**

Run temporary tests RED/GREEN, delete them, run mvn compile and npm run build.

Backend commit:

    git commit -S1B7FF0413D02217B -m "feat: add tenant and global administrators"

Frontend commit:

    git commit -S1B7FF0413D02217B -m "feat: support tenant-aware administration"

### Task 4: Display independent frontend and backend versions

**Problem group:** 1 — version and short-hash display.

**Files:**
- Modify: backend/pom.xml
- Create: backend/src/main/java/top/hetao/shiyuanticketmp/common/version/VersionController.java
- Create: backend/src/main/java/top/hetao/shiyuanticketmp/common/version/VersionInfoService.java
- Modify: front/package.json
- Modify: front/package-lock.json
- Modify: front/vite.config.js
- Create: front/src/api/version.js
- Create: front/src/utils/version.js
- Modify: front/src/layout/index.vue
- Modify: front/src/views/settings/index.vue
- Temporary tests: backend VersionInfoServiceTest.java; front temp-version.test.mjs

- [ ] **Step 1: Write temporary failing version tests**

Backend test constructs VersionInfoService with BuildProperties version 1.0.7 and GitProperties commit 1234567890, asserting:

~~~java
assertThat(service.getVersion()).isEqualTo("1.0.7");
assertThat(service.getCommit()).isEqualTo("1234567");
~~~

Front Node test imports formatVersionLabel and asserts:

~~~javascript
assert.equal(formatVersionLabel('1.0.10', 'abcdef123'), 'v1.0.10 (abcdef1)')
assert.equal(formatVersionLabel(undefined, undefined), 'vunknown (unknown)')
~~~

Confirm both RED because services/helpers do not exist.

- [ ] **Step 2: Generate backend build metadata**

Set backend project version to 1.0.7. Add Spring Boot build-info execution and io.github.git-commit-id:git-commit-id-maven-plugin version 9.2.0 with revision at initialize, generateGitPropertiesFile true, failOnNoGitDirectory false, and abbreviated commit output.

VersionInfoService uses ObjectProvider<BuildProperties> and ObjectProvider<GitProperties>, falls back to APP_VERSION and GIT_COMMIT environment variables, then unknown. VersionController returns the project response shape at GET /api/system/version and requires login.

- [ ] **Step 3: Inject frontend metadata**

Set package.json and package-lock.json to 1.0.10. Vite reads package.json and resolves commit by CI environment first, then execFileSync git rev-parse --short=7 HEAD, then unknown. Define __APP_VERSION__ and __GIT_COMMIT__ with JSON.stringify.

version.js exports immutable frontend metadata and formatVersionLabel. version API calls /system/version.

- [ ] **Step 4: Display and verify**

Layout loads backend version without blocking navigation and shows compact frontend/backend labels. Settings information tab shows separate version and commit rows and replaces hard-coded v1.0.0.

Run temporary tests to GREEN, delete them, then run mvn package -DskipTests and npm run build. Inspect target/classes/META-INF/build-info.properties, target/classes/git.properties, and built frontend assets for non-placeholder values.

- [ ] **Step 5: Commit**

Backend:

    git commit -S1B7FF0413D02217B -m "feat: expose backend build version"

Frontend:

    git commit -S1B7FF0413D02217B -m "feat: display frontend and backend versions"

### Task 5: Add per-tenant external integration switches

**Problem group:** 3 — external inbound, close callback, and DingTalk switches.

**Files:**
- Create: backend/src/main/resources/db/migration/V19__create_tenant_integration_settings.sql
- Create: backend/src/main/java/top/hetao/shiyuanticketmp/tenant/setting/entity/SysTenantSetting.java
- Create: backend/src/main/java/top/hetao/shiyuanticketmp/tenant/setting/mapper/SysTenantSettingMapper.java
- Create: backend/src/main/java/top/hetao/shiyuanticketmp/tenant/setting/service/TenantIntegrationSettingService.java
- Create: backend/src/main/java/top/hetao/shiyuanticketmp/tenant/setting/controller/TenantIntegrationSettingController.java
- Create: backend/src/main/java/top/hetao/shiyuanticketmp/tenant/setting/controller/dto/TenantIntegrationSettingsRequest.java
- Create: backend/src/main/java/top/hetao/shiyuanticketmp/tenant/setting/controller/dto/TenantIntegrationSettings.java
- Modify: backend/src/main/java/top/hetao/shiyuanticketmp/webhook/receiver/CargoOwnerWebhookController.java
- Modify: backend/src/main/java/top/hetao/shiyuanticketmp/workorder/listener/WorkOrderWebhookListener.java
- Modify: backend/src/main/java/top/hetao/shiyuanticketmp/webhook/sender/DingTalkDispatcher.java
- Modify: backend/src/main/java/top/hetao/shiyuanticketmp/webhook/sender/CargoOwnerDispatcher.java
- Create: front/src/api/admin/settings.js
- Modify: front/src/views/settings/index.vue
- Modify: front/src/router/index.js
- Modify: front/src/layout/index.vue
- Temporary tests: backend TenantIntegrationSettingServiceTest.java, IntegrationSwitchFlowTest.java; front temp-settings-contract.test.mjs

- [ ] **Step 1: Write temporary failing settings tests**

Test missing rows default to true, tenant 100 false does not affect tenant 200, inbound disabled prevents AI/create calls, close disabled prevents CargoOwner enqueue, and DingTalk disabled prevents DingTalk enqueue while assignment remains successful.

Confirm RED because there is no tenant setting service.

- [ ] **Step 2: Create settings storage and API**

V19 creates sys_tenant_setting with id, tenant_id, setting_key, setting_value, timestamps, deleted, and unique (tenant_id, setting_key). Backfill all three keys as true for enabled business tenants.

TenantIntegrationSettingService exposes:

~~~java
TenantIntegrationSettings get(Long tenantId);
TenantIntegrationSettings update(Long tenantId, TenantIntegrationSettingsRequest request);
boolean externalInboundEnabled(Long tenantId);
boolean externalCloseCallbackEnabled(Long tenantId);
boolean dingTalkPushEnabled(Long tenantId);
~~~

Controller GET/PUT /api/admin/settings/external-integrations always uses TenantContext.requireTenantId and settings:view/settings:update permissions.

- [ ] **Step 3: Gate external flows by work-order tenant**

CargoOwnerWebhookController verifies signature, resolves senderStaffId and tenant, checks externalInboundEnabled before AI parse and insert, and returns HTTP 503 with the normal code/message payload when disabled.

WorkOrderWebhookListener checks externalCloseCallbackEnabled for CLOSE and dingTalkPushEnabled for ASSIGN using event tenantId. Disabled channels do not enqueue or validate credentials.

Move dispatcher URL/credential validation from startup to actual dispatch so an unused disabled channel cannot prevent application startup.

- [ ] **Step 4: Replace fake settings UI**

Add a real External Integration tab with the three switches. Load GET on mount for authorized users, keep server values on load failure, disable controls while saving, PUT all three Boolean values, and refill from the returned server object.

Settings route/link requires settings:view; save requires settings:update. Remove fake success handlers for unimplemented basic/email/storage/security forms or render those fields read-only so no action claims persistence.

- [ ] **Step 5: Verify, delete tests, and commit**

Run focused backend tests and temporary Node contract test, delete them, then mvn compile and npm run build.

Backend:

    git commit -S1B7FF0413D02217B -m "feat: add tenant integration switches"

Frontend:

    git commit -S1B7FF0413D02217B -m "feat: manage tenant integration switches"

### Task 6: Automatically assign external inbound work orders after one minute

**Problem group:** 2 — one-minute automatic role assignment.

**Files:**
- Create: backend/src/main/resources/db/migration/V20__add_external_auto_assignment.sql
- Modify: backend/src/main/java/top/hetao/shiyuanticketmp/ShiyuanTicketMpApplication.java
- Modify: backend/src/main/java/top/hetao/shiyuanticketmp/workorder/entity/WorkOrder.java
- Modify: backend/src/main/java/top/hetao/shiyuanticketmp/webhook/receiver/CargoOwnerWebhookController.java
- Modify: backend/src/main/java/top/hetao/shiyuanticketmp/workorder/mapper/WorkOrderMapper.java
- Modify: backend/src/main/java/top/hetao/shiyuanticketmp/workorder/service/WorkOrderService.java
- Modify: backend/src/main/java/top/hetao/shiyuanticketmp/workorder/service/impl/WorkOrderServiceImpl.java
- Create: backend/src/main/java/top/hetao/shiyuanticketmp/workorder/scheduler/ExternalInboundAutoAssignmentScheduler.java
- Temporary tests: backend ExternalInboundAutoAssignmentSchedulerTest.java, AtomicRoleAssignmentTest.java

- [ ] **Step 1: Write temporary failing scheduler and atomic-claim tests**

Tests assert:

- a webhook order younger than 60 seconds is not eligible;
- an order at least 60 seconds old is eligible;
- normal API orders, non-PENDING orders, and any already assigned order are excluded;
- two concurrent claim calls produce affected-row counts 1 and 0;
- only the successful claim publishes ASSIGN;
- a tenant-100 failure does not stop tenant 200;
- TenantContext is empty after each candidate.

Confirm RED because source marker, mapper claim, and scheduler do not exist.

- [ ] **Step 2: Add source marker and scan index**

V20 adds created_via_webhook Boolean default false and a composite index beginning with created_via_webhook and status and covering assigned fields, created_at, and id.

WorkOrder maps createdViaWebhook. CargoOwnerWebhookController sets true; authenticated/API creates set or default false.

- [ ] **Step 3: Implement bounded candidate scan**

WorkOrderMapper has an internal-bypass query selecting at most configured batch size where created_via_webhook=1, status=PENDING, all assignment fields null, deleted=0, and created_at <= cutoff ordered by created_at,id.

Scheduler configuration:

~~~properties
workorder.external-auto-assignment.enabled=true
workorder.external-auto-assignment.fixed-delay-ms=10000
workorder.external-auto-assignment.minimum-age-seconds=60
workorder.external-auto-assignment.batch-size=100
~~~

The scheduler uses an internal bypass only for candidate discovery, then uses TenantContext.useTenant(order.tenantId) per item.

- [ ] **Step 4: Implement atomic role claim**

WorkOrderMapper update uses WHERE id, tenant_id, created_via_webhook, PENDING, null assignment fields, and deleted=0. It sets assignee_role=WAREHOUSE_ADMIN, status=IN_PROGRESS, assigned_at and updated_at.

WorkOrderService publishes an ASSIGN event only when affected rows equals one. Zero is a benign lost race. Permanent missing-role errors are logged with tenant/order identifiers; other retryable failures leave the row unchanged.

- [ ] **Step 5: Verify, delete tests, and commit**

Run the focused tests repeatedly to exercise concurrency, delete them, run mvn compile, and confirm no new test files.

Backend:

    git commit -S1B7FF0413D02217B -m "feat: auto-assign external inbound orders"

No frontend commit is required.

### Task 7: Fix confirmed authentication, async, notification, dead-letter, and frontend permission bugs

**Problem group:** 6 — additional confirmed bugs.

**Files:**
- Create: backend/src/main/resources/db/migration/V21__fix_tenant_async_records.sql
- Modify: backend/src/main/java/top/hetao/shiyuanticketmp/common/config/SaTokenConfig.java
- Modify: backend/src/main/java/top/hetao/shiyuanticketmp/audit/controller/AuditLogController.java
- Modify: backend/src/main/java/top/hetao/shiyuanticketmp/workorder/event/WorkOrderEvent.java
- Modify: backend/src/main/java/top/hetao/shiyuanticketmp/workorder/event/WorkOrderCommentEvent.java
- Modify: backend/src/main/java/top/hetao/shiyuanticketmp/workorder/event/WorkOrderStateChangedEvent.java
- Modify: backend/src/main/java/top/hetao/shiyuanticketmp/workorder/listener/WorkOrderWebhookListener.java
- Modify: backend/src/main/java/top/hetao/shiyuanticketmp/workorder/listener/WorkOrderCacheListener.java
- Modify: backend/src/main/java/top/hetao/shiyuanticketmp/audit/listener/WorkOrderAuditListener.java
- Modify: backend/src/main/java/top/hetao/shiyuanticketmp/webhook/sender/WebhookMessageAggregator.java
- Create: backend/src/main/java/top/hetao/shiyuanticketmp/webhook/sender/DispatchResult.java
- Modify: backend/src/main/java/top/hetao/shiyuanticketmp/webhook/deadletter/WebhookDeadLetterRecord.java
- Modify: backend/src/main/java/top/hetao/shiyuanticketmp/webhook/deadletter/WebhookDeadLetterService.java
- Modify: backend/src/main/java/top/hetao/shiyuanticketmp/webhook/deadletter/WebhookDeadLetterMapper.java
- Modify: backend/src/main/java/top/hetao/shiyuanticketmp/webhook/deadletter/controller/DeadLetterController.java
- Modify: front/src/views/login/index.vue
- Modify: front/src/layout/index.vue
- Modify: front/src/router/index.js
- Modify: front/src/directives/permission.js
- Create: front/src/utils/permission.js
- Temporary tests: backend AuthenticationBoundaryTest.java, WebhookAggregationIsolationTest.java, DeadLetterRetryStateTest.java; front temp-permission-guard.test.mjs

- [ ] **Step 1: Write temporary failing security and notification tests**

Tests prove anonymous audit/monitor access is rejected, events execute after commit, aggregation buckets differ by tenant/channel/conversation/sender, continuous events flush at max age/size, and dead letters remain pending until the correct channel reports success.

Frontend Node test proves empty menu data produces no privileged fallback and route authorization rejects missing permissions/global-admin status.

- [ ] **Step 2: Restore authentication and endpoint permissions**

SaInterceptor calls StpUtil.checkLogin for every non-whitelisted request. Keep only login, signed cargo-owner webhook, generic signed webhook receiver, error, and favicon anonymous. Add explicit business permission annotations to audit, webhook monitor, file, dead-letter, and other previously annotation-free administrative reads.

- [ ] **Step 3: Make async effects after-commit and tenant-explicit**

Every work-order/comment event carries tenantId. Replace ordinary async EventListener side-effect handling with TransactionalEventListener phase AFTER_COMMIT plus Async where appropriate. Each listener enters TenantContext.useTenant(event.tenantId) and clears automatically.

- [ ] **Step 4: Isolate aggregation buckets**

WebhookMessageAggregator keys buffers by tenantId, ChannelTarget, conversationId, and senderStaffId. DingTalk may use a stable tenant/channel target key; cargo-owner never mixes recipients. Add configurable max batch size and max wait; reaching either flushes without allowing continuous traffic to postpone forever.

- [ ] **Step 5: Correct dead-letter channel retries**

V21 adds tenant_id correctness/indexes for audit/comment data and channel, target_url, conversation_id, sender_staff_id fields to webhook_dead_letter. Backfill tenant where determinable without inventing ownership; leave ambiguous legacy rows explicitly platform-owned and unavailable to tenants.

DispatchResult is an immutable success/failure value containing success, statusCode, and message. DeadLetterRecord stores tenant and channel metadata. Retry dispatches through synchronous retry methods on CargoOwnerDispatcher or DingTalkDispatcher according to channel and updates RESOLVED only when DispatchResult.success is true. Async enqueue is not treated as delivery success.

- [ ] **Step 6: Fix frontend credential and permission behavior**

permission.js exports canAccessRoute(routeMeta, authState) and hasPermission(required, permissions). Remember tenantCode and username only. On load, remove any password from legacy rememberedLogin. Delete privileged fallbackMenus; empty menu means empty menu. Add route meta permission/globalAdmin constraints and enforce them after /auth/me with canAccessRoute. Permission directive removes unauthorized elements rather than only hiding them and uses hasPermission. The temporary Node test imports these pure helpers.

- [ ] **Step 7: Verify, delete tests, and commit**

Run focused backend tests, temporary Node tests, mvn compile, and npm run build. Delete new tests. Inspect staged files to ensure no tests/results/screenshots/build output.

Backend:

    git commit -S1B7FF0413D02217B -m "fix: close security and notification gaps"

Frontend:

    git commit -S1B7FF0413D02217B -m "fix: enforce frontend permission boundaries"

### Task 8: Synchronize API documentation and perform final verification

**Files:**
- No repository source file is created solely for API documentation.
- Update Apifox project 8260787.

- [ ] **Step 1: Synchronize Apifox**

Update login, /auth/me, switch-tenant, tenant CRUD/options/admin assignment, tenant integration settings, version, and work-order tenant-name contracts. Include Long/string examples and permission/error responses.

- [ ] **Step 2: Run repository verification**

Backend:

    mvn compile
    mvn package -DskipTests

Frontend:

    npm run build

Run focused temporary tests again only if needed to investigate a failure, then delete them.

- [ ] **Step 3: Audit migrations and production baseline**

Confirm Flyway resolves unique ordered versions V1, ..., V10, V10.1, ..., V21. Verify the bootstrap SQL creates only flyway_schema_history and inserts only baseline version 16. Confirm V17-V21 are newer and will run afterward.

- [ ] **Step 4: Audit commits and repository cleanliness**

For both repositories:

    git log origin/master..HEAD --show-signature --format=fuller
    git diff --check origin/master..HEAD
    git status --short

Every new commit must have a good signature and required email. No newly created test file or generated artifact may be tracked. Existing unrelated untracked user files may remain.

- [ ] **Step 5: Perform final code review**

Dispatch a final spec-compliance reviewer for the complete design, then a code-quality/security reviewer. Fix all Critical and Important findings using the responsible task implementer, re-review, and keep any necessary fix inside the corresponding problem-group commit by amending and re-signing before delivery.
