# Tenant Login and Write Guard Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 修复租户生命周期共享锁查询的 JSqlParser 500，并实现公开租户选择、带租户编码的工单深链以及完整安全的登录回跳。

**Architecture:** 后端仅在两个已确认含 `LOCK IN SHARE MODE` 的 Mapper 方法上跳过租户行拦截器，并用最小公开 DTO 暴露启用租户；Webhook 事件携带稳定租户编码，由统一 URL 构造器附加到详情链接。前端用可直接由 Node 运行的纯函数统一默认租户选择、登录位置构造和回跳校验，路由守卫与 Axios 401 共享该逻辑。

**Tech Stack:** Java 17、Spring Boot 3.0.2、MyBatis-Plus 3.5.5、JUnit 5/Mockito/MockMvc、Vue 3、Vue Router 4、Axios、Element Plus、Node `node:test`、Playwright。

---

## File map and repository boundaries

- `backend/` 与 `front/` 是两个独立 Git 仓库；所有 Maven/Git 命令从 `backend/` 执行，所有 npm/Node/Git 命令从 `front/` 执行。
- 后端新增测试放在 `backend/src/test/java/top/hetao/shiyuanticketmp/` 对应包下；现有测试会加载 `dev` 配置，真实 Mapper/MockMvc 集成测试需要 `application-dev.yml` 所依赖的 MySQL、Redis 和环境变量可用。
- 前端没有 test npm script；纯函数测试固定使用 `node --test tests/tenant-login.test.mjs`，不得添加虚构的 `npm test`、lint 或 typecheck 命令。
- 当前全仓生产代码中只有 `SysTenantMapper.selectEnabledByIdForShare` 和 `selectNotDeletedByIdForShare` 含 `LOCK IN SHARE MODE`。最终用 `rg` 再做一次门禁；若未来出现新的命中，必须逐条判断是否会经过 JSqlParser，不能假设本次两个注解会保护其他 Mapper 方法。

### Task 1: Shared-lock parser regression and tenant-only interceptor bypass

**Files:**
- Create: `backend/src/test/java/top/hetao/shiyuanticketmp/tenant/mapper/SysTenantMapperInterceptorIgnoreTest.java`
- Create: `backend/src/test/java/top/hetao/shiyuanticketmp/tenant/mapper/SysTenantMapperIntegrationTest.java`
- Modify: `backend/src/main/java/top/hetao/shiyuanticketmp/tenant/mapper/SysTenantMapper.java:1-45`

- [ ] **Step 1: Write a dependency-free failing annotation contract test**

```java
class SysTenantMapperInterceptorIgnoreTest {
    @Test
    void sharedLockMethodsIgnoreOnlyTenantLineInterceptor() throws Exception {
        for (String name : List.of("selectEnabledByIdForShare", "selectNotDeletedByIdForShare")) {
            Method method = SysTenantMapper.class.getMethod(name, Long.class);
            InterceptorIgnore ignore = method.getAnnotation(InterceptorIgnore.class);
            assertThat(ignore).as(name).isNotNull();
            assertThat(ignore.tenantLine()).isEqualTo("true");
            for (Method member : InterceptorIgnore.class.getDeclaredMethods()) {
                if (!member.getName().equals("tenantLine")) {
                    assertThat(member.invoke(ignore)).isEqualTo(member.getDefaultValue());
                }
            }
        }
    }
}
```

- [ ] **Step 2: Run the RED test without external services**

Run from `backend/`: `mvn -Dtest=SysTenantMapperInterceptorIgnoreTest test`

Expected: FAIL because both methods currently have no `@InterceptorIgnore` annotation.

- [ ] **Step 3: Write the real MyBatis-Plus/MySQL regression test**

```java
@SpringBootTest
@Transactional
class SysTenantMapperIntegrationTest {
    @Autowired SysTenantMapper mapper;

    @Test
    void sharedLockQueriesExecuteThroughConfiguredInterceptorChain() {
        try (TenantContext.Scope ignored = TenantContext.useTenant(0L)) {
            assertThat(mapper.selectEnabledByIdForShare(0L)).isNotNull();
            assertThat(mapper.selectNotDeletedByIdForShare(0L)).isNotNull();
        }
    }
}
```

- [ ] **Step 4: Confirm the integration RED when dev services are available**

Run from `backend/`: `mvn -Dtest=SysTenantMapperIntegrationTest test`

Expected before the fix: FAIL before SQL reaches MySQL with the MyBatis-Plus/JSqlParser parse error at `LOCK IN SHARE MODE`. If MySQL/Redis is unavailable, record that environment failure separately; the dependency-free annotation test remains the mandatory RED/GREEN gate.

- [ ] **Step 5: Add the minimal production fix**

```java
import com.baomidou.mybatisplus.annotation.InterceptorIgnore;

@InterceptorIgnore(tenantLine = "true")
@Select("""
    SELECT id, tenant_code, tenant_name, status, created_at, updated_at, deleted
    FROM sys_tenant
    WHERE id = #{tenantId} AND status = 1 AND deleted = 0
    LOCK IN SHARE MODE
    """)
SysTenant selectEnabledByIdForShare(@Param("tenantId") Long tenantId);

@InterceptorIgnore(tenantLine = "true")
@Select("""
    SELECT id, tenant_code, tenant_name, status, created_at, updated_at, deleted
    FROM sys_tenant
    WHERE id = #{tenantId} AND deleted = 0
    LOCK IN SHARE MODE
    """)
SysTenant selectNotDeletedByIdForShare(@Param("tenantId") Long tenantId);
```

Do not change the predicates, `LOCK IN SHARE MODE`, transaction callers, `selectByIdForUpdate`, or global interceptor configuration.

- [ ] **Step 6: Run GREEN and search for unhandled shared locks**

Run from `backend/`:

```powershell
mvn -Dtest=SysTenantMapperInterceptorIgnoreTest test
mvn -Dtest=SysTenantMapperIntegrationTest test
rg -n "LOCK\s+IN\s+SHARE\s+MODE" src/main -g '!target/**'
```

Expected: both tests PASS when dev services are reachable; `rg` reports exactly the two annotated SQL statements plus explanatory comments, with no other executable shared-lock SQL.

- [ ] **Step 7: Commit the backend fix**

```powershell
git add src/main/java/top/hetao/shiyuanticketmp/tenant/mapper/SysTenantMapper.java src/test/java/top/hetao/shiyuanticketmp/tenant/mapper/SysTenantMapperInterceptorIgnoreTest.java src/test/java/top/hetao/shiyuanticketmp/tenant/mapper/SysTenantMapperIntegrationTest.java
git commit -m "fix: bypass tenant parser for shared tenant locks"
```

### Task 2: Public enabled-tenant options endpoint

**Files:**
- Create: `backend/src/main/java/top/hetao/shiyuanticketmp/auth/controller/dto/TenantOptionResponse.java`
- Create: `backend/src/main/java/top/hetao/shiyuanticketmp/auth/AuthTenantOptionController.java`
- Create: `backend/src/test/java/top/hetao/shiyuanticketmp/tenant/service/TenantServiceLoginOptionsTest.java`
- Create: `backend/src/test/java/top/hetao/shiyuanticketmp/auth/AuthTenantOptionControllerIntegrationTest.java`
- Modify: `backend/src/main/java/top/hetao/shiyuanticketmp/tenant/service/TenantService.java:35-60`
- Modify: `backend/src/main/java/top/hetao/shiyuanticketmp/common/config/SaTokenConfig.java:39-56`

- [ ] **Step 1: Write RED service tests for projection, filtering and deterministic ordering**

```java
@ExtendWith(MockitoExtension.class)
class TenantServiceLoginOptionsTest {
    @Mock SysTenantMapper mapper;
    @Mock RoleProvisioningService roles;
    @Mock TenantMenuProvisioningService menus;

    @Test
    void platformIsFirstThenNameAndCodeAndDtoLeaksNoId() {
        when(mapper.selectList(any())).thenReturn(List.of(
            tenant(12L, "zeta", "乙仓", 1),
            tenant(0L, "platform", "平台租户", 1),
            tenant(11L, "alpha", "甲仓", 1)));
        TenantService service = new TenantService(mapper, roles, menus);
        assertThat(service.listLoginOptions()).containsExactly(
            new TenantOptionResponse("platform", "平台租户"),
            new TenantOptionResponse("alpha", "甲仓"),
            new TenantOptionResponse("zeta", "乙仓"));
        assertThat(TenantOptionResponse.class.getRecordComponents())
            .extracting(RecordComponent::getName)
            .containsExactly("tenantCode", "tenantName");
    }

    private static SysTenant tenant(Long id, String code, String name, int status) {
        SysTenant tenant = new SysTenant();
        tenant.setId(id);
        tenant.setTenantCode(code);
        tenant.setTenantName(name);
        tenant.setStatus(status);
        tenant.setDeleted(0);
        return tenant;
    }
}
```

- [ ] **Step 2: Run RED**

Run: `mvn -Dtest=TenantServiceLoginOptionsTest test`

Expected: FAIL because `TenantOptionResponse` and `listLoginOptions()` do not exist.

- [ ] **Step 3: Implement the two-field DTO and enabled query**

```java
public record TenantOptionResponse(String tenantCode, String tenantName) {}

@Transactional(readOnly = true)
public List<TenantOptionResponse> listLoginOptions() {
    Comparator<TenantOptionResponse> order = Comparator
        .comparing((TenantOptionResponse item) -> !"platform".equals(item.tenantCode()))
        .thenComparing(TenantOptionResponse::tenantName)
        .thenComparing(TenantOptionResponse::tenantCode);
    return tenantMapper.selectList(new LambdaQueryWrapper<SysTenant>()
            .eq(SysTenant::getStatus, 1))
        .stream()
        .map(item -> new TenantOptionResponse(item.getTenantCode(), item.getTenantName()))
        .sorted(order)
        .toList();
}
```

MyBatis-Plus logical deletion continues to exclude `deleted=1`; do not expose `SysTenant` directly.

- [ ] **Step 4: Add the endpoint and exact unauthenticated whitelist entries**

```java
@RestController
@RequestMapping("/api/auth")
public class AuthTenantOptionController {
    private final TenantService tenantService;

    public AuthTenantOptionController(TenantService tenantService) {
        this.tenantService = tenantService;
    }

    @GetMapping("/tenant-options")
    public Map<String, Object> options() {
        return Map.of("code", 200,
            "message", "获取租户选项成功",
            "data", tenantService.listLoginOptions());
    }
}
```

Add `"/api/auth/tenant-options"` to both `AUTH_EXCLUDE_PATHS` and `TENANT_EXCLUDE_PATHS`; do not broaden the whitelist to all `/api/auth/**` because password/profile endpoints require authentication.

- [ ] **Step 5: Write and run the unauthenticated MockMvc integration test with owned test data**

```java
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class AuthTenantOptionControllerIntegrationTest {
    @Autowired MockMvc mockMvc;
    @Autowired SysTenantMapper tenantMapper;

    @Test
    void endpointIsPublicAndReturnsOnlyEnabledMinimalOptions() throws Exception {
        tenantMapper.insert(tenant(910000001L, "plan-enabled", "计划启用租户", 1));
        tenantMapper.insert(tenant(910000002L, "plan-disabled", "计划停用租户", 0));

        mockMvc.perform(get("/api/auth/tenant-options"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.code").value(200))
            .andExpect(jsonPath("$.message").value("获取租户选项成功"))
            .andExpect(jsonPath("$.data[0].tenantCode").value("platform"))
            .andExpect(jsonPath("$.data[*].tenantCode", hasItem("plan-enabled")))
            .andExpect(jsonPath("$.data[*].tenantCode", not(hasItem("plan-disabled"))))
            .andExpect(jsonPath("$.data[0].id").doesNotExist())
            .andExpect(jsonPath("$.data[0].status").doesNotExist());
    }

    private static SysTenant tenant(Long id, String code, String name, int status) {
        SysTenant tenant = new SysTenant();
        tenant.setId(id);
        tenant.setTenantCode(code);
        tenant.setTenantName(name);
        tenant.setStatus(status);
        tenant.setDeleted(0);
        return tenant;
    }
}
```

Run: `mvn -Dtest=TenantServiceLoginOptionsTest,AuthTenantOptionControllerIntegrationTest test`

Expected: PASS without an Authorization header; the integration test requires the same dev services as the existing `@SpringBootTest`.

- [ ] **Step 6: Commit the endpoint**

```powershell
git add src/main/java/top/hetao/shiyuanticketmp/auth src/main/java/top/hetao/shiyuanticketmp/tenant/service/TenantService.java src/main/java/top/hetao/shiyuanticketmp/common/config/SaTokenConfig.java src/test/java/top/hetao/shiyuanticketmp/auth src/test/java/top/hetao/shiyuanticketmp/tenant/service/TenantServiceLoginOptionsTest.java
git commit -m "feat: expose public tenant login options"
```

### Task 3: Tenant-aware webhook detail links

**Files:**
- Create: `backend/src/main/java/top/hetao/shiyuanticketmp/webhook/sender/WorkOrderDetailUrlBuilder.java`
- Create: `backend/src/test/java/top/hetao/shiyuanticketmp/webhook/sender/WorkOrderDetailUrlBuilderTest.java`
- Create: `backend/src/test/java/top/hetao/shiyuanticketmp/webhook/sender/WebhookTenantLinkTest.java`
- Create: `backend/src/test/java/top/hetao/shiyuanticketmp/tenant/service/TenantServiceTenantCodeTest.java`
- Modify: `backend/src/main/java/top/hetao/shiyuanticketmp/workorder/event/WorkOrderEvent.java:25-70`
- Modify: `backend/src/main/java/top/hetao/shiyuanticketmp/workorder/listener/WorkOrderWebhookListener.java:30-120`
- Modify: `backend/src/main/java/top/hetao/shiyuanticketmp/tenant/service/TenantService.java`
- Modify: `backend/src/main/java/top/hetao/shiyuanticketmp/webhook/sender/DingTalkDispatcher.java:145-230`
- Modify: `backend/src/main/java/top/hetao/shiyuanticketmp/webhook/sender/CargoOwnerDispatcher.java:160-230`

- [ ] **Step 1: Write RED URL tests**

```java
assertThat(WorkOrderDetailUrlBuilder.build(
    "https://example.test/base?source=push", 123L, "tenant-100"))
    .isEqualTo("https://example.test/base/workorder/detail/123?source=push&tenantCode=tenant-100");
assertThat(WorkOrderDetailUrlBuilder.build("  ", 123L, "tenant-100")).isNull();
assertThatThrownBy(() -> WorkOrderDetailUrlBuilder.build(
    "https://example.test", 123L, null))
    .isInstanceOf(IllegalArgumentException.class)
    .hasMessageContaining("tenantCode");
```

Run: `mvn -Dtest=WorkOrderDetailUrlBuilderTest test`

Expected: FAIL because the builder does not exist.

- [ ] **Step 2: Implement URI-safe construction**

```java
static String build(String baseUrl, Long workOrderId, String tenantCode) {
    if (baseUrl == null || baseUrl.isBlank()) return null;
    if (workOrderId == null || tenantCode == null || tenantCode.isBlank()) {
        throw new IllegalArgumentException("workOrderId and tenantCode are required");
    }
    return UriComponentsBuilder.fromUriString(baseUrl.trim())
        .pathSegment("workorder", "detail", workOrderId.toString())
        .queryParam("tenantCode", tenantCode)
        .build().encode().toUriString();
}
```

- [ ] **Step 3: Define strict tenant-code lookup and put it into the asynchronous event snapshot**

Add `private String tenantCode;` to `WorkOrderEvent`. Add `TenantService.getTenantCode(Long)` using the global `sys_tenant` lookup. It must reject null/negative IDs, missing/logically deleted tenant rows, and blank codes with `WorkOrderException`; it must return the stable code for both enabled and disabled non-deleted tenants so an already-committed derived event does not silently lose its deep-link identity.

```java
@Transactional(readOnly = true)
public String getTenantCode(Long tenantId) {
    if (tenantId == null || tenantId < 0) {
        throw new WorkOrderException("租户 ID 非法");
    }
    SysTenant tenant = tenantMapper.selectById(tenantId);
    if (tenant == null || tenant.getTenantCode() == null || tenant.getTenantCode().isBlank()) {
        throw new WorkOrderException("租户不存在或缺少租户编码: " + tenantId);
    }
    return tenant.getTenantCode();
}
```

Create `TenantServiceTenantCodeTest` with mocked mapper cases for enabled, disabled, missing and blank-code rows. Inject `TenantService` into `WorkOrderWebhookListener`, and set the code in both state-change and comment payload construction before `aggregator.submit(payload)`.

```java
payload.setTenantCode(tenantService.getTenantCode(event.getTenantId()));
```

- [ ] **Step 4: Write RED dispatcher body tests, then use the builder in both channels**

Construct the dispatchers without Spring or external services, set each `workOrderDetailBaseUrl` with `ReflectionTestUtils`, call package-visible `prepareBatchBody`, and assert both serialized bodies contain `tenantCode=tenant-100`, preserve `source=push`, and omit `处理链接`/`查看详情` when the base URL is blank.

```java
ObjectMapper objectMapper = new ObjectMapper();
WebhookDeadLetterService deadLetters = mock(WebhookDeadLetterService.class);
DingTalkDispatcher dingTalk = new DingTalkDispatcher(objectMapper, deadLetters);
CargoOwnerDispatcher cargoOwner = new CargoOwnerDispatcher(objectMapper, deadLetters);
ReflectionTestUtils.setField(dingTalk, "workOrderDetailBaseUrl",
    "https://example.test/base?source=push");
ReflectionTestUtils.setField(cargoOwner, "workOrderDetailBaseUrl",
    "https://example.test/base?source=push");

WorkOrderEvent event = new WorkOrderEvent();
event.setWorkOrderId(123L);
event.setTenantId(100L);
event.setTenantCode("tenant-100");
event.setStatus("ASSIGNED");
event.setCreatedAt(LocalDateTime.of(2026, 7, 14, 10, 0));

assertThat(new String(dingTalk.prepareBatchBody(List.of(event)), UTF_8))
    .contains("source=push&tenantCode=tenant-100");
assertThat(new String(cargoOwner.prepareBatchBody(List.of(event)), UTF_8))
    .contains("source=push&tenantCode=tenant-100");
```

Replace hand-built concatenation with:

```java
String detailUrl = WorkOrderDetailUrlBuilder.build(
    workOrderDetailBaseUrl, e.getWorkOrderId(), e.getTenantCode());
if (detailUrl != null) {
    sb.append("处理链接：").append(detailUrl).append("\n");
}
```

Use the concrete channel statements `sb.append("- **处理链接**：[查看详情](").append(detailUrl).append(")\n");` and `sb.append("处理链接：").append(detailUrl).append("\n");`. Change DingTalk's `validateHttpUrl(workOrderDetailBaseUrl, ..., true)` call to `required=false`; the robot access token and secret remain required, while a blank detail base URL now sends the notification without a link. Add a DingTalk test that invokes `buildRequestUrl()` with a valid access token/secret and blank detail base URL and asserts it does not throw because of the detail URL.

Add negative dispatcher assertions that an event with null/blank `tenantCode` throws `IllegalArgumentException` during body preparation rather than sending a tenant-ambiguous link.

Run: `mvn -Dtest=TenantServiceTenantCodeTest,WorkOrderDetailUrlBuilderTest,WebhookTenantLinkTest test`

Expected: PASS for DingTalk and cargo-owner messages, existing base query preservation, and blank base URL.

- [ ] **Step 5: Commit webhook work**

```powershell
git add src/main/java/top/hetao/shiyuanticketmp/webhook/sender src/main/java/top/hetao/shiyuanticketmp/workorder/event/WorkOrderEvent.java src/main/java/top/hetao/shiyuanticketmp/workorder/listener/WorkOrderWebhookListener.java src/main/java/top/hetao/shiyuanticketmp/tenant/service/TenantService.java src/test/java/top/hetao/shiyuanticketmp/webhook/sender
git commit -m "feat: include tenant code in work order links"
```

### Task 4: Frontend tenant API and pure login utilities

**Files:**
- Create: `front/src/utils/tenant-login.js`
- Create: `front/tests/tenant-login.test.mjs`
- Modify: `front/src/api/auth.js:1-20`

- [ ] **Step 1: Write RED Node tests for selection and redirect contracts**

```js
import test from 'node:test'
import assert from 'node:assert/strict'
import {
  buildLoginLocation,
  firstQueryString,
  resolvePostLoginTarget,
  resolveSafeRedirect,
  selectInitialTenant,
} from '../src/utils/tenant-login.js'

test('URL tenant wins and unavailable URL tenant does not silently fall back', () => {
  assert.deepEqual(selectInitialTenant({ requestedTenant: 'acme', rememberedTenant: 'old', options }),
    { tenantCode: 'acme', requestedUnavailable: false })
  assert.deepEqual(selectInitialTenant({ requestedTenant: 'missing', rememberedTenant: 'old', options }),
    { tenantCode: '', requestedUnavailable: true })
})

test('remembered, platform, then first option are fallback order', () => {
  assert.equal(selectInitialTenant({ rememberedTenant: 'old', options }).tenantCode, 'old')
  assert.equal(selectInitialTenant({ options }).tenantCode, 'platform')
  assert.equal(selectInitialTenant({ options: [{ tenantCode: 'first', tenantName: 'First' }] }).tenantCode, 'first')
})
test('login location preserves fullPath query and hash', () => {
  assert.deepEqual(buildLoginLocation({ path: '/workorder/detail/123',
    fullPath: '/workorder/detail/123?tenantCode=acme&foo=bar#timeline',
    query: { tenantCode: 'acme', foo: 'bar' } }), {
      path: '/login', query: { tenant: 'acme', redirect: '/workorder/detail/123?tenantCode=acme&foo=bar#timeline' }
    })
})
test('post-login redirect rejects external, protocol-relative and login-loop values', () => {
  assert.equal(resolveSafeRedirect('https://evil.test'), '/')
  assert.equal(resolveSafeRedirect('//evil.test/path'), '/')
  assert.equal(resolveSafeRedirect('/login?redirect=/workorder/list'), '/')
  assert.equal(resolveSafeRedirect('/workorder/list?foo=bar#top'), '/workorder/list?foo=bar#top')
})

test('query arrays use the same first-string normalization for tenant and tenantCode', () => {
  assert.equal(firstQueryString(['acme', 'ignored']), 'acme')
  assert.equal(firstQueryString([]), '')
})

test('platform login never returns to a business work-order deep link', () => {
  assert.equal(resolvePostLoginTarget('/workorder/detail/123?tenantCode=platform', 'platform'), '/system/tenant')
  assert.equal(resolvePostLoginTarget('/workorder/detail/123?tenantCode=acme', 'acme'),
    '/workorder/detail/123?tenantCode=acme')
})
```

Run from `front/`: `node --test tests/tenant-login.test.mjs`

Expected: FAIL with module-not-found.

- [ ] **Step 2: Implement pure functions with exact signatures**

Export `firstQueryString(value)`, `selectInitialTenant({ requestedTenant, rememberedTenant, options })`, `buildLoginLocation(route)`, `resolveSafeRedirect(value, fallback = '/')`, and `resolvePostLoginTarget(value, tenantCode)`. Use `firstQueryString` for both login-page `tenant` and business-deep-link `tenantCode`, so Vue Router array values are normalized identically. `resolveSafeRedirect` accepts only strings beginning with `/` but not `//`, and rejects values whose path portion is `/login`; `resolvePostLoginTarget` sends `platform` to `/system/tenant` instead of returning it to `/workorder/**`.

- [ ] **Step 3: Add the API wrapper**

```js
export function getLoginTenantOptions() {
  return request({ url: '/auth/tenant-options', method: 'get' })
}
```

- [ ] **Step 4: Run GREEN and commit in the frontend repository**

```powershell
node --test tests/tenant-login.test.mjs
git add src/api/auth.js src/utils/tenant-login.js tests/tenant-login.test.mjs
git commit -m "feat: add tenant login utilities"
```

Expected: all Node tests PASS.

### Task 5: Searchable login tenant selector and URL-synchronized defaults

**Files:**
- Modify: `front/src/views/login/index.vue:1-145`
- Modify: `front/tests/tenant-login.test.mjs`

- [ ] **Step 1: Extend RED tests for empty options and normalized query values**

Add explicit cases for `options=[]`, `route.query.tenant` supplied as a Vue Router array, remembered tenants no longer present, and first-option fallback when `platform` is absent.

Run: `node --test tests/tenant-login.test.mjs`

Expected: FAIL until normalization is implemented.

- [ ] **Step 2: Replace the free-text tenant input**

Use `el-select` with `filterable`, `data-testid="tenant-select"`, `@change="handleTenantChange"`, and options whose value is `tenantCode` and label is `${tenantName} (${tenantCode})`. Add `data-testid="username-input"`, `data-testid="password-input"`, and `data-testid="login-button"` to the other controls so E2E never relies on positional inputs. Add an `el-alert` plus retry button for API failure, an empty-state message when no options exist, and disable login while options are loading, failed, empty, or the URL-requested tenant is unavailable.

- [ ] **Step 3: Load saved account then apply the fixed selection priority**

Keep `rememberedLogin` as a dedicated ref/object rather than mutating the form during parsing. Keep `tenantOptionsLoading` and `loginSubmitting` as separate refs so a completed options request cannot clear the login-submit spinner and vice versa. On mount: parse remembered login, set only the remembered username, fetch `getLoginTenantOptions()`, read options from `res.data`, then call:

```js
const selected = selectInitialTenant({
  requestedTenant: route.query.tenant,
  rememberedTenant: rememberedLogin?.tenantCode,
  options: tenantOptions.value,
})
loginForm.tenantCode = selected.tenantCode
requestedTenantUnavailable.value = selected.requestedUnavailable
```

An unavailable explicit URL tenant must leave the selector empty and show a warning; it must not silently choose the remembered tenant/platform. After choosing any valid initial fallback (remembered, `platform`, or first option), immediately canonicalize the address with `router.replace({ path: '/login', query: { ...route.query, tenant: selected.tenantCode } })` while preserving `redirect`.

- [ ] **Step 4: Synchronize user selection and use safe post-login replacement**

```js
await router.replace({
  path: '/login',
  query: { ...route.query, tenant: selectedTenantCode },
})

await router.replace(resolvePostLoginTarget(route.query.redirect, loginForm.tenantCode))
```

Watch `() => route.query.tenant` and rerun the same normalized selection routine whenever browser navigation changes the query. Guard the watcher so the component's own canonical `router.replace` does not loop. Keep the login request payload field named `tenantCode` and retain the existing remembered username/password-clearing behavior.

The component-level wiring is verified by Vite build and Task 7 Playwright flows; Node tests prove only the imported pure functions, not Vue lifecycle or Element Plus behavior.

- [ ] **Step 5: Verify and commit**

```powershell
node --test tests/tenant-login.test.mjs
npm run build
git add src/views/login/index.vue tests/tenant-login.test.mjs
git commit -m "feat: select tenant on login page"
```

Expected: tests PASS and Vite build completes successfully.

### Task 6: FullPath-preserving route guard and idempotent Axios 401 redirect

**Files:**
- Modify: `front/src/router/index.js:115-165`
- Modify: `front/src/utils/request.js:36-52,100-135`
- Modify: `front/tests/tenant-login.test.mjs`

- [ ] **Step 1: Add RED cases for guard/401 route objects**

Cover `/workorder/detail/123?tenantCode=acme&foo=bar#timeline`, absence of `tenantCode`, repeated calls receiving the same immutable login location, protocol-relative redirect rejection, and `/login?redirect=...` loop rejection.

- [ ] **Step 2: Use the shared location in every router guard login branch**

Replace both `next(\`/login?redirect=${to.path}\`)` calls with:

```js
next(buildLoginLocation(to))
```

This includes the no-token branch and the `getUserInfo()`/menu-loading catch branch. Do not use `to.path`; `to.fullPath` is the source of the preserved query/hash.

- [ ] **Step 3: Make 401 preserve the first full route without redirect races**

In `request.js`, always call `userStore.resetState()` first, including when the 401 arrives on `/login`; then inspect the current route. Derive the location from `router.currentRoute.value` and use `router.replace(location)`. Keep one in-flight redirect Promise; later concurrent 401 responses return while it exists, log navigation failures in `.catch()`, and clear the Promise in `.finally()`. If already on `/login`, stop after state reset. Do not reset this guard merely because an unrelated request returns 200.

```js
let loginRedirectPromise = null
function handle401(message) {
  useUserStore().resetState()
  if (router.currentRoute.value.path === '/login' || loginRedirectPromise) return
  const location = buildLoginLocation(router.currentRoute.value)
  showError(message || '未登录或登录已过期')
  loginRedirectPromise = router.replace(location)
    .catch(error => console.error('跳转登录页失败', error))
    .finally(() => { loginRedirectPromise = null })
}
```

- [ ] **Step 4: Run tests/build and commit**

```powershell
node --test tests/tenant-login.test.mjs
npm run build
git add src/router/index.js src/utils/request.js tests/tenant-login.test.mjs
git commit -m "fix: preserve tenant deep links through login"
```

Expected: pure tests PASS; build succeeds; no remaining string-built `redirect=${to.path}` or `redirect=${currentPath}` login URL remains. Reading `router.currentRoute.value.path` solely to detect that the app is already on `/login` is intentional.

### Task 7: Update existing direct E2E scripts

**Files:**
- Modify: `front/e2e-test.cjs:57-109,181`
- Modify: `front/e2e-api-test.cjs:7-10,51-65,159-190`

- [ ] **Step 1: Fix login fixtures without inventing business-tenant credentials**

Keep only the repository-documented platform default in source:

```js
const platformAccount = {
  tenantCode: 'platform',
  username: 'admin',
  password: 'admin123',
}

const businessAccount = {
  tenantCode: process.env.E2E_TENANT_CODE,
  username: process.env.E2E_USERNAME,
  password: process.env.E2E_PASSWORD,
}

const hasBusinessAccount = Object.values(businessAccount).every(Boolean)
```

Remove the obsolete hard-coded warehouse/cargo credentials and business tenant code; do not provide fallback business passwords. API tests that require a business tenant run only when `hasBusinessAccount` is true; otherwise log one explicit `SKIP: set E2E_TENANT_CODE, E2E_USERNAME and E2E_PASSWORD` line.

- [ ] **Step 2: Fix UI selectors for the new tenant select**

For the basic platform login, open `/login?tenant=platform`, wait for `[data-testid="tenant-select"]` to contain `platform`, fill `[data-testid="username-input"] input` and `[data-testid="password-input"] input`, then click `[data-testid="login-button"]`. Assert platform login lands on `/system/tenant`; it must not be used to test a work-order return path.

- [ ] **Step 3: Add the approved deep-link scenario**

Only when all three `E2E_TENANT_CODE`, `E2E_USERNAME`, and `E2E_PASSWORD` values are present, create a brand-new Playwright browser context with no localStorage/cookies, then navigate to:

```js
const deepLink = `/workorder/detail/123?tenantCode=${encodeURIComponent(businessAccount.tenantCode)}&foo=bar#timeline`
const loggedOutContext = await browser.newContext()
const deepLinkPage = await loggedOutContext.newPage()
await deepLinkPage.goto(`${BASE_URL}${deepLink}`)
```

Assert the login route has `tenant=<E2E_TENANT_CODE>` and a decoded `redirect` exactly equal to `deepLink`. Use the four test IDs to verify/change the selected business tenant, fill the environment-provided username/password, and log in. Assert the final URL preserves the business `tenantCode`, `foo=bar`, and `#timeline`. Close `loggedOutContext` in `finally`. When any environment value is absent, do not partially execute this scenario; print the explicit SKIP message from Step 1.

- [ ] **Step 4: Syntax-check, then run only when services are available**

```powershell
node --check e2e-test.cjs
node --check e2e-api-test.cjs
node e2e-test.cjs
node e2e-api-test.cjs
```

Expected: both syntax checks always PASS. With backend on `9860` and frontend on `3000`, tenant selection/login/deep-link assertions PASS; if services are not running, report E2E as skipped due environment rather than claiming it passed.

- [ ] **Step 5: Commit E2E changes**

```powershell
git add e2e-test.cjs e2e-api-test.cjs
git commit -m "test: cover tenant-aware login deep links"
```

### Task 8: Cross-project verification and Apifox synchronization

**Files:**
- Modify in Apifox project `8260787`: public auth tenant options endpoint and DingTalk/cargo-owner detail-link descriptions
- No local `API.md`

- [ ] **Step 1: Run the backend focused suite**

Run from `backend/`:

```powershell
mvn -Dtest=SysTenantMapperInterceptorIgnoreTest,TenantServiceLoginOptionsTest,TenantServiceTenantCodeTest,WorkOrderDetailUrlBuilderTest,WebhookTenantLinkTest test
```

Expected: this dependency-free unit/focused set PASS without MySQL or Redis. Then, when dev MySQL/Redis are reachable, run `mvn -Dtest=SysTenantMapperIntegrationTest,AuthTenantOptionControllerIntegrationTest test` and expect both integration tests to PASS; if services are unavailable, report only those two as environment-skipped and do not weaken the unit-test gate.

- [ ] **Step 2: Compile the complete backend**

Run: `mvn compile`

Expected: `BUILD SUCCESS`.

- [ ] **Step 3: Run frontend direct tests and production build**

Run from `front/`:

```powershell
node --test tests/tenant-login.test.mjs
node --check e2e-test.cjs
node --check e2e-api-test.cjs
npm run build
```

Expected: Node tests and syntax checks PASS; Vite reports a successful production build.

- [ ] **Step 4: Perform the shared-lock and redirect completion audit**

```powershell
rg -n "LOCK\s+IN\s+SHARE\s+MODE" src/main -g '!target/**'
rg -n "redirect=\$\{to\.path\}|redirect=\$\{currentPath\}" ../front/src/router ../front/src/utils/request.js
```

Expected: backend shared-lock executable SQL is limited to the two annotated Mapper methods; frontend old string-built path-only redirect patterns have zero matches. The intentional `/login` equality check against `currentRoute.value.path` is not an audit failure.

- [ ] **Step 5: Sync Apifox project `8260787`**

Create/update `GET /api/auth/tenant-options` as no-auth, with the exact `code/message/data[{tenantCode,tenantName}]` schema and no ID/status fields. Update both webhook channel descriptions so example detail links are `/workorder/detail/{id}?tenantCode={tenantCode}`, note preservation of pre-existing base URL query parameters, and document that blank detail base URL omits the link. Do not create a local API markdown file.

- [ ] **Step 6: Review independent repository status and commit only intended changes**

```powershell
git -C . status --short
git -C ../front status --short
```

Expected: only intentional implementation/test files remain; preserve all pre-existing untracked files listed before implementation. If Apifox synchronization required a final local correction, commit it in the corresponding repository with a scoped message; do not combine backend and frontend paths in one commit.
