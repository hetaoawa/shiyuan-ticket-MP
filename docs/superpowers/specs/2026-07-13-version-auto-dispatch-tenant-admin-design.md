# Version, External Automation, and Tenant Administration Design

## Scope

This design coordinates changes across the Spring Boot repository (`backend/`) and the Vue repository (`front/`). It covers:

1. Independent frontend and backend version plus short Git hash display.
2. Automatic assignment of externally submitted inbound work orders after one minute.
3. Per-tenant switches for external inbound submission, external close callbacks, and DingTalk pushes.
4. Tenant-isolation and tenant-name correctness.
5. Tenant system administrators and global system-administrator tenant switching.
6. Confirmed security, tenancy, notification, and frontend permission bugs found during review.
7. Flyway-managed migrations for new environments and a safe version-16 production baseline bootstrap.

The backend release version for this work is `1.0.7`. The frontend release version is `1.0.10`. Each repository continues to version independently.

## Repository and Delivery Baseline

Both repositories use `master` as their remote primary branch; neither has a `main` branch. Local unpushed commits were reviewed and rejected as a baseline because they contained functional and concurrency defects. Both repositories were reset to the fetched `origin/master` and now use branch `feat/version-auto-dispatch-tenant-admin`.

Repository-local Git configuration uses:

- email `79517899+hetaoawa@users.noreply.github.com`
- signing key `1B7FF0413D02217B`
- OpenPGP commit signing enabled

The six requested problem groups will be committed separately in each repository where that group changes code. Confirmed additional bugs are grouped into the sixth commit. The design and implementation-plan commits are preparatory documentation commits and do not combine functional changes.

New test source files and ad-hoc test scripts are temporary verification artifacts only. They are removed before every functional commit and are never committed. The already tracked baseline test file is not deleted as part of this work. Generated build, browser, coverage, report, screenshot, and upload-test artifacts are added to the appropriate repository `.gitignore`; existing user artifacts are not deleted.

## Architecture

### Tenant master data

Add a global `sys_tenant` table with:

- `id`
- `tenant_code`, unique and immutable after creation
- `tenant_name`
- `status`, with enabled and disabled states
- creation, update, and logical-deletion metadata

Tenant options and names must come from this table. The application must no longer infer tenants by grouping `sys_user.tenant_id` or synthesize names such as `租户100`.

Existing tenant IDs are backfilled into `sys_tenant`. Tenant `0` is the platform tenant and is not a valid active tenant for business operations.

### Per-tenant settings

Add a tenant-scoped `sys_tenant_setting` table keyed by `(tenant_id, setting_key)`. The supported Boolean keys are:

- `externalInboundEnabled`
- `externalCloseCallbackEnabled`
- `dingTalkPushEnabled`

Missing rows resolve to `true` to preserve existing behavior. Reads and writes are restricted to the active tenant. A global administrator may change a tenant's settings only after switching to that tenant.

### Administrator roles

Use distinct role codes:

- `GLOBAL_SYSTEM_ADMIN`: platform identity, allowed only for users whose principal tenant is `0`.
- `SYSTEM_ADMIN`: tenant administrator, instantiated within each business tenant.
- `WAREHOUSE_ADMIN`: tenant cloud-warehouse administrator.
- `CARGO_OWNER`: tenant cargo-owner user.

`SYSTEM_ADMIN` never grants a tenant-filter bypass. Assigning roles requires the user and role to belong to the same tenant. A tenant user cannot receive `GLOBAL_SYSTEM_ADMIN`.

Tenant administrator assignment is represented only by the tenant's `SYSTEM_ADMIN` role bindings. No separate `admin_user_id` source of truth is introduced. The administrator-setting operation replaces the selected tenant's administrator bindings transactionally after validating that every selected user belongs to that tenant.

### Authentication and active tenant

Login accepts `tenantCode`, `username`, and `password`, resolving duplicate usernames unambiguously within a tenant. Platform global administrators log in with the platform tenant code.

The authenticated session stores:

- `principalTenantId`: the user's immutable owning tenant for the session
- `activeTenantId`: the selected business tenant
- `globalAdmin`: whether the principal has `GLOBAL_SYSTEM_ADMIN`

Tenant users receive their principal tenant as the active tenant. Global administrators must switch to an enabled business tenant before accessing tenant business data. There is no cross-tenant aggregate mode for work orders, users, roles, files, comments, or audit data.

`TenantContext` distinguishes an HTTP active tenant from an internal bypass scope. HTTP roles and request parameters cannot enable bypass. Full-table scans use an explicit internal scope with automatic cleanup. Missing HTTP tenant context fails closed instead of silently becoming tenant `0`.

### Tenant switching

The backend exposes authenticated tenant-option and switch endpoints. Switching validates global-administrator identity and tenant enabled status, then updates only `activeTenantId`.

After a successful switch, the frontend reloads `/auth/me`, permissions, and menus, clears tenant-specific cached state, and remounts or refreshes the current business view. The header always shows the active tenant. Non-global users do not see the switcher.

## Feature Design

### Independent build versions

Backend Maven build metadata provides version `1.0.7` and a seven-character Git commit hash. The build generates Spring Boot build information and Git properties, but remains buildable when `.git` is unavailable. Environment-provided commit metadata may be used as a fallback; otherwise the value is `unknown`.

The backend exposes an authenticated system-version endpoint containing only version and abbreviated commit information.

The frontend package version becomes `1.0.10`, with `package-lock.json` kept in sync. Vite injects the frontend version and short commit hash at build time, preferring CI metadata and falling back to `git rev-parse --short=7 HEAD` without failing builds that lack `.git`.

The authenticated layout header shows compact frontend and backend versions. The system-information view shows both versions and hashes separately. The frontend never bakes a backend version into its artifact because the two applications may be deployed independently.

### External inbound and automatic assignment

The inbound flow is:

1. Verify the external request signature.
2. Resolve `senderStaffId` to a system user without tenant filtering.
3. Derive the tenant from that mapped user.
4. Read that tenant's `externalInboundEnabled` setting.
5. If disabled, return an explicit service-disabled response before AI parsing or database creation.
6. Within a bounded tenant scope, create a `PENDING` work order with the derived `tenantId`, submitter, external source marker, and external context fields.

Externally created work orders become eligible for automatic assignment only after `created_at` is at least 60 seconds old. A scheduler runs every 10 seconds, scans eligible candidates in bounded batches, and processes each candidate within its tenant scope.

Assignment uses a database atomic conditional update requiring:

- matching work-order ID and tenant ID
- external-source marker set
- `PENDING` status
- no assignee user
- no assignee role
- no assignment timestamp

Only an affected-row count of one is a successful claim. An affected-row count of zero means another scheduler instance or a user already handled the work order; no duplicate assignment event is emitted. Successful claims assign the tenant's `WAREHOUSE_ADMIN` role and publish exactly one assignment event. Retryable business failures leave the work order eligible for a later scan. The candidate query has a configurable batch size and a supporting composite index.

### External integration switches

The close-callback setting is evaluated using the work order's tenant when a close event is handled. When disabled, the work order still closes normally but no cargo-owner callback is queued.

The DingTalk setting is evaluated using the work order's tenant when an assignment notification is handled. When disabled, assignment still succeeds but no DingTalk message is queued.

External dispatcher credentials are validated lazily when an enabled channel actually sends. Disabling a channel for a tenant does not break that tenant's business flow. Event payloads carry tenant ID explicitly and are processed only after the surrounding transaction commits.

Outbound messages are bucketed by tenant, channel, and recipient target. Events belonging to different conversations, users, or tenants can never share a payload. Aggregation has both a normal delay and a maximum batch age or size so continuous traffic cannot postpone delivery forever.

### Tenant-aware business access

All tenant-owned records use the active tenant for reads and writes. Explicit service-layer checks remain in place at cache and relationship boundaries where SQL interception can be bypassed.

Work-order cache keys include tenant ID, and a cache hit is accepted only when the cached order tenant equals the active tenant. Assignees and mapped external users must belong to the work-order tenant.

Work-order list and detail responses include `tenantId` and `tenantName`. Tenant and snowflake IDs remain strings in the frontend.

Comments validate access to the target work order and copy its tenant ID. Audit events, notification events, and dead-letter records carry tenant ID explicitly so asynchronous execution never depends on a request thread-local.

Tables containing real tenant-owned data, including audit and comments, are not globally excluded from tenant filtering. `express_trace` is explicitly a global carrier-response cache keyed by tracking number, matching the existing global Redis cache. Its legacy `tenant_id` column is retained with constant value `0` for migration compatibility but is not used or presented as an isolation boundary; the entity and service document this global-cache behavior.

## API Design

The implementation adds or updates these contracts under `/api`:

- authenticated system-version read endpoint
- tenant directory CRUD/options endpoints
- tenant administrator read/update endpoint
- tenant-settings read/update endpoint for the active tenant
- login request including `tenantCode`
- authenticated active-tenant switch endpoint
- `/auth/me` fields for principal tenant, active tenant, active tenant name, and global-administrator status
- work-order list/detail tenant-name fields

Settings endpoints require settings view/update permissions and the active tenant. Tenant-directory mutation and global-role management require `GLOBAL_SYSTEM_ADMIN`. Tenant administrators may manage only their active tenant and cannot modify tenant ownership or global roles.

All changed contracts are synchronized to Apifox project `8260787`; no local `API.md` is introduced.

## Confirmed Bug Fixes

The sixth functional group includes these confirmed defects:

1. The global Sa-Token interceptor must call login validation; only explicit login and signed-webhook paths remain anonymous.
2. Cross-tenant role assignment and permission resolution must validate user/role tenant consistency.
3. Cached work orders must not bypass tenant checks.
4. Comments, audit logs, express data according to its selected semantics, and dead letters must not leak across tenants or default to tenant `0` in asynchronous code.
5. Dead letters store tenant, channel, and target metadata and are marked resolved only after the correct channel confirms a successful retry.
6. Message aggregation cannot combine different tenants or recipient targets.
7. The frontend no longer stores plaintext passwords in local storage and removes any legacy stored password.
8. Empty menu results do not fall back to privileged static administration menus.
9. Management and settings routes enforce frontend permission metadata in addition to backend authorization.
10. The settings page performs real reads and writes and never reports success without persistence.

## Error Handling

- Disabled inbound integration returns an explicit service-disabled error without creating a work order.
- Missing active tenant rejects tenant business operations.
- Unknown or disabled tenants cannot be selected.
- Unauthorized tenant or global-role changes return access-denied errors without partial writes.
- Automatic-assignment claim conflicts are normal no-op outcomes, not errors.
- Retryable automatic-assignment failures remain pending and are logged with tenant and work-order identifiers.
- Disabled outbound channels do not change work-order status outcomes.
- Enabled channels with invalid credentials fail delivery and enter the tenant-aware dead-letter flow.
- Tenant context and internal bypass scopes are always cleared in `finally`/scope-close paths.

## Flyway and Migration Strategy

Add the Spring Boot-managed Flyway dependency and enable Flyway for application startup with:

- migration location `classpath:db/migration`
- validation enabled
- out-of-order execution disabled
- automatic baseline disabled
- Flyway `clean` disabled

Failing to prepare a non-empty existing schema therefore stops startup instead of silently baselining an unknown database.

All legacy migration scripts from `V1` through `V16` become tracked application resources so a new empty database can be built from scratch. The duplicate legacy `V10` filenames are normalized without changing their SQL semantics: the resubmit/force-reject migration remains `V10`, and the cargo-owner-field migration becomes `V10.1`. Flyway version `16` is greater than both `10` and `10.1`, so a version-16 production baseline skips every legacy migration.

The implementation provides `src/main/resources/db/bootstrap/enable_flyway_existing_production.sql`, outside Flyway's migration location. An operator runs this file once against an existing production database only after confirming that all legacy schema changes through `V16` are already present. The script:

1. creates the Flyway schema-history table using the exact layout required by the pinned Flyway version;
2. refuses to overwrite non-empty or inconsistent Flyway history;
3. inserts a successful `BASELINE` row at version `16`;
4. leaves all application business tables unchanged.

After the bootstrap SQL succeeds, enabling/deploying the Flyway-integrated application validates the baseline and executes only migrations newer than `16`. Empty new databases do not run the bootstrap SQL and execute the full `V1`, `V2`, ..., `V10`, `V10.1`, ..., migration chain normally.

New migrations are ordered by functional dependency:

- `V17`: tenant master data, existing-tenant backfill, and tenant-name indexes
- `V18`: global/tenant administrator role split, existing administrator migration, and role provisioning
- `V19`: per-tenant setting storage and default rows
- `V20`: external-source marker and automatic-assignment scan index
- `V21`: tenant/channel/target dead-letter fields plus audit/comment tenant-key corrections

`express_trace` remains a global tracking-number cache and requires no tenant-key migration. Its legacy tenant column remains constant `0`.

Each script documents required deployment order. Application code must not assume a new column exists before its corresponding script is applied. The production runbook order is: database backup, legacy-schema preflight, execute the production baseline SQL, deploy the Flyway-enabled application, verify schema-history rows through the latest version, and then enable traffic.

## Verification

Backend verification includes focused tests for:

- login tenant-code resolution and anonymous endpoint rejection
- global versus tenant administrator authorization
- active-tenant switching and disabled-tenant rejection
- same-role and same-username data across two tenants
- cross-tenant role-binding rejection
- work-order SQL and cache isolation
- work-order tenant-name enrichment
- comment, audit, and dead-letter tenant inheritance
- all three per-tenant switches and default compatibility behavior
- automatic-assignment age threshold, candidate filtering, batch bounds, multi-tenant scope cleanup, and concurrent claim idempotency
- message grouping by tenant and recipient
- correct-channel dead-letter retry state transitions
- Flyway migration discovery with unique versions
- empty-schema migration from `V1` to latest when a disposable MySQL instance is available
- production baseline SQL structure and version-16 history semantics

Frontend verification includes:

- independent version/hash display and unknown fallback
- tenant-code login
- global-administrator tenant switching and state refresh
- tenant/system-administrator route and menu matrices
- real settings read, update, refresh, and permission handling
- work-order tenant display
- preservation of 19-digit IDs as strings
- removal of plaintext remembered passwords and privileged fallback menus

Final commands are run from the child repositories:

- backend: `mvn compile` plus focused tests whose dependencies are available
- frontend: `npm run build`

Focused tests may be created to drive and verify an implementation, but every newly created test source/script is deleted after execution and before staging. No new test file, generated fixture, browser screenshot, report, coverage directory, test upload, Maven output, or Vite output may appear in a commit.

The final review also checks commit signatures, commit email, unique Flyway migration versions, absence of newly committed test files, ignored build/test artifacts, clean tracked status, and Apifox synchronization.
