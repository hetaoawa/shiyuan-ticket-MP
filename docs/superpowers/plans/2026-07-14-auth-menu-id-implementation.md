# Authentication, Menu Navigation, and Opaque ID Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Deliver browser-safe login memory and focus flow, lossless tenant-aware 401 recovery with legacy-session migration, permission-correct recursive navigation, and consistent string-safe ID display/copy behavior.

**Architecture:** Make `AuthTenantService` the single validator/migrator of Sa-Token tenant identity and expose the active tenant code so the frontend can snapshot it before clearing state. Build user navigation by post-order pruning on the backend, then defensively normalize it against Vue Router's actual registered paths and render it recursively. Centralize all business-ID presentation in `OpaqueId.vue`; only pagination counters may use `Number(...)`.

**Tech Stack:** Java 17, Spring Boot 3.0.2, Sa-Token 1.38.0, MyBatis-Plus 3.5.5, JUnit 5/Mockito, Vue 3, Vue Router 4, Pinia, Element Plus, Node test runner, Playwright.

---

## Contract invariants

- Remembered login data contains only `tenantCode` and `username`; passwords never enter Web Storage or custom reversible ciphertext. Native inputs use `autocomplete="username"` and `autocomplete="current-password"`.
- Focus waits for tenant options, then follows tenant -> username -> password -> submit; restored tenant plus username focuses password.
- The sole login query field is `tenantCode`. Selection precedence is explicit query, tenant embedded in `redirect`, remembered tenant, `platform`, first enabled option.
- A 401 snapshots the complete `fullPath` and valid active tenant code before `resetState()`. Unsafe redirects and login loops remain rejected.
- Legacy normal-user session key `tenantId` migrates to canonical keys only after authoritative user/tenant validation. Corrupt identity or disabled tenant logs out and returns HTTP 401; a global administrator with no active tenant remains a valid “select tenant” state.
- User menu trees exclude BUTTON nodes, apply permissions to MENU nodes, prune empty DIR nodes post-order, and always return `children: []`. Admin full trees remain complete.
- Frontend navigation drops empty/duplicate/unregistered paths without inventing paths from `menuCode`; `/` resolves to the first accessible registered route.
- Every business ID is handled as `String(value)`. Only pagination `total`, `page`, and `size` may be numeric.

### Task 1: Add the active tenant code to canonical auth context

**Files:**
- Modify: `src/main/java/top/hetao/shiyuanticketmp/auth/AuthTenantContext.java`
- Modify: `src/main/java/top/hetao/shiyuanticketmp/auth/service/AuthTenantService.java`
- Modify: `src/main/java/top/hetao/shiyuanticketmp/auth/AuthController.java`
- Test: `src/test/java/top/hetao/shiyuanticketmp/auth/service/AuthTenantServiceContextTest.java`

- [ ] **Step 1: Write the failing context test**

```java
@Test
void normalLoginStoresActiveTenantCodeAndReturnsIt() {
    SaSession session = new SaSession("normal-session");
    SysUser user = user(9L, 100L);
    SysTenant tenant = tenant(100L, "acme", "Acme");
    AuthTenantContext context = service.initializeSession(
            new AuthTenantService.ResolvedLogin(user, tenant, false), session);
    assertThat(session.getString(AuthTenantService.ACTIVE_TENANT_CODE)).isEqualTo("acme");
    assertThat(context).isEqualTo(new AuthTenantContext(100L, 100L, "acme", "Acme", false));
}
```

Also assert global login clears active ID/code/name and `AuthController.putContext()` returns `activeTenantCode` on login, `/me`, and switch responses.

- [ ] **Step 2: Run and confirm RED**

Run: `mvn -Dtest=AuthTenantServiceContextTest test`

Expected: compilation fails because `ACTIVE_TENANT_CODE` and the five-field record do not exist.

- [ ] **Step 3: Extend the canonical context**

```java
public record AuthTenantContext(
        Long principalTenantId,
        Long activeTenantId,
        String activeTenantCode,
        String activeTenantName,
        boolean globalAdmin) {
}
```

Add `ACTIVE_TENANT_CODE = "activeTenantCode"`. In `initializeSession`, store `resolved.tenant().getTenantCode()` for normal users and delete it for global administrators. In `switchTenant`, set target ID/code/name together. Read all five fields in `readContext` and add:

```java
private static void putContext(Map<String, Object> result, AuthTenantContext context) {
    result.put("principalTenantId", context.principalTenantId());
    result.put("activeTenantId", context.activeTenantId());
    result.put("activeTenantCode", context.activeTenantCode());
    result.put("activeTenantName", context.activeTenantName());
    result.put("globalAdmin", context.globalAdmin());
}
```

- [ ] **Step 4: Verify and commit**

Run: `mvn -Dtest=AuthTenantServiceContextTest test`

Expected: PASS for normal/global initialization and tenant switching.

```bash
git add src/main/java/top/hetao/shiyuanticketmp/auth src/test/java/top/hetao/shiyuanticketmp/auth/service/AuthTenantServiceContextTest.java
git commit -m "feat: expose active tenant code in auth context"
```

### Task 2: Migrate valid legacy sessions and invalidate corrupt ones

**Files:**
- Create: `src/main/java/top/hetao/shiyuanticketmp/auth/exception/InvalidAuthSessionException.java`
- Modify: `src/main/java/top/hetao/shiyuanticketmp/auth/service/AuthTenantService.java`
- Modify: `src/main/java/top/hetao/shiyuanticketmp/common/config/SaTokenConfig.java`
- Modify: `src/main/java/top/hetao/shiyuanticketmp/common/exception/GlobalExceptionHandler.java`
- Test: `src/test/java/top/hetao/shiyuanticketmp/auth/service/AuthTenantServiceLegacySessionTest.java`
- Test: `src/test/java/top/hetao/shiyuanticketmp/common/config/TenantInterceptorInvalidSessionTest.java`

- [ ] **Step 1: Write failing legacy-session tests**

```java
@Test
void migratesEnabledNormalUserLegacyTenantKey() {
    SaSession session = new SaSession("legacy");
    session.set("tenantId", "100");
    when(userMapper.selectByIdIgnoreTenant(9L)).thenReturn(user(9L, 100L));
    when(tenantService.requireEnabled(100L)).thenReturn(tenant(100L, "acme", "Acme"));
    AuthTenantContext context = service.readOrMigrateContext(session, 9L);
    assertThat(context.activeTenantCode()).isEqualTo("acme");
    assertThat(session.get(AuthTenantService.PRINCIPAL_TENANT_ID)).isEqualTo(100L);
    assertThat(session.get(AuthTenantService.ACTIVE_TENANT_ID)).isEqualTo(100L);
}

@ParameterizedTest
@MethodSource("corruptSessions")
void rejectsCorruptOrDisabledSession(SaSession session) {
    assertThatThrownBy(() -> service.readOrMigrateContext(session, 9L))
            .isInstanceOf(InvalidAuthSessionException.class);
}
```

Cases must include nonnumeric IDs, missing authoritative user, principal/user mismatch, non-global active/principal mismatch, missing both canonical and legacy identity, and disabled active tenant. Add a valid global-admin case with no active tenant.

- [ ] **Step 2: Run and confirm RED**

Run: `mvn -Dtest=AuthTenantServiceLegacySessionTest,TenantInterceptorInvalidSessionTest test`

Expected: FAIL because migration and invalid-session exception are absent.

- [ ] **Step 3: Implement authoritative migration**

Add `LEGACY_TENANT_ID = "tenantId"` and this public boundary:

```java
@Transactional(readOnly = true)
public AuthTenantContext readOrMigrateContext(SaSession session, long loginId) {
    SysUser user;
    try (TenantContext.Scope ignored = TenantContext.useInternalBypass()) {
        user = userMapper.selectByIdIgnoreTenant(loginId);
    }
    if (user == null) throw new InvalidAuthSessionException("登录身份已失效");
    boolean authoritativeGlobal = Long.valueOf(0L).equals(user.getTenantId())
            && userService.getPrincipalRoleCodes(loginId).contains("GLOBAL_SYSTEM_ADMIN");
    Long principal = safeLong(session.get(PRINCIPAL_TENANT_ID));
    if (principal == null) {
        boolean partialCanonicalIdentity = session.get(ACTIVE_TENANT_ID) != null
                || session.get(ACTIVE_TENANT_CODE) != null
                || session.get(ACTIVE_TENANT_NAME) != null
                || session.get(GLOBAL_ADMIN) != null;
        if (partialCanonicalIdentity) throw new InvalidAuthSessionException("登录身份上下文损坏");
        return migrateLegacy(session, user, authoritativeGlobal);
    }
    Object globalValue = session.get(GLOBAL_ADMIN);
    if (!(globalValue instanceof Boolean sessionGlobal)
            || !principal.equals(user.getTenantId())
            || sessionGlobal != authoritativeGlobal) {
        throw new InvalidAuthSessionException("登录身份上下文损坏");
    }
    Long active = safeLong(session.get(ACTIVE_TENANT_ID));
    if (active == null) {
        if (!authoritativeGlobal) throw new InvalidAuthSessionException("活动租户缺失");
        return new AuthTenantContext(principal, null, null, null, true);
    }
    if (!authoritativeGlobal && !active.equals(principal)) {
        throw new InvalidAuthSessionException("活动租户与登录身份不匹配");
    }
    SysTenant tenant = requireSessionTenant(active);
    session.set(ACTIVE_TENANT_CODE, tenant.getTenantCode());
    session.set(ACTIVE_TENANT_NAME, tenant.getTenantName());
    return readContext(session);
}
```

`migrateLegacy` requires a parseable `tenantId` equal to a normal user's authoritative tenant; for an authoritative global admin it requires legacy `tenantId=0` and leaves active tenant empty. `requireSessionTenant` translates disabled/missing tenant errors into `InvalidAuthSessionException`. `safeLong` translates malformed values rather than leaking `NumberFormatException`.

Change `TenantInterceptor` to depend on `AuthTenantService`, call `readOrMigrateContext(StpUtil.getSession(), parsedLoginId)`, and use the returned active ID. On `InvalidAuthSessionException`, call `StpUtil.logout()` before rethrowing. A valid global admin with no active tenant still receives existing stable `WorkOrderException("请先选择租户")` on tenant-bound APIs, not 401. Map `InvalidAuthSessionException` to HTTP/body 401 in `GlobalExceptionHandler`.

- [ ] **Step 4: Verify and commit**

Run: `mvn -Dtest=AuthTenantServiceLegacySessionTest,TenantInterceptorInvalidSessionTest test`

Expected: PASS; the interceptor test verifies logout precedes the 401 exception and global no-active is not invalidated.

```bash
git add src/main/java/top/hetao/shiyuanticketmp/auth src/main/java/top/hetao/shiyuanticketmp/common/config/SaTokenConfig.java src/main/java/top/hetao/shiyuanticketmp/common/exception/GlobalExceptionHandler.java src/test/java/top/hetao/shiyuanticketmp/auth src/test/java/top/hetao/shiyuanticketmp/common/config/TenantInterceptorInvalidSessionTest.java
git commit -m "fix: migrate legacy tenant sessions safely"
```

### Task 3: Use browser password management and deterministic login focus

**Files:**
- Modify: `../front/src/views/login/index.vue`
- Modify: `../front/src/utils/remembered-login.js`
- Create: `../front/src/utils/login-focus.js`
- Create: `../front/tests/login-focus.test.mjs`
- Modify: `../front/tests/tenant-login.test.mjs`

- [ ] **Step 1: Write failing Node/source tests**

```javascript
test('focus waits for options and restored account targets password', () => {
  assert.equal(resolveLoginFocus({ optionsLoaded: false }), null)
  assert.equal(resolveLoginFocus({ optionsLoaded: true, tenantCode: 'acme', username: 'alice' }), 'password')
  assert.equal(resolveLoginFocus({ optionsLoaded: true, tenantCode: 'acme', username: '' }), 'username')
  assert.equal(resolveLoginFocus({ optionsLoaded: true, tenantCode: '', username: '' }), 'tenant')
})

test('legacy remembered password is discarded', () => {
  assert.deepEqual(parseRememberedLogin('{"tenantCode":"acme","username":"alice","password":"secret"}'), {
    tenantCode: 'acme', username: 'alice',
  })
})
```

Source assertions must require username/password autocomplete attributes and forbid any `localStorage`/`sessionStorage` password write.

- [ ] **Step 2: Run and confirm RED**

Run from `../front`: `node --test tests/login-focus.test.mjs tests/tenant-login.test.mjs`

Expected: FAIL because `login-focus.js`, refs, autocomplete, and focus handlers are absent.

- [ ] **Step 3: Implement focus after async tenant loading**

```javascript
export function resolveLoginFocus({ optionsLoaded, tenantCode, username }) {
  if (!optionsLoaded) return null
  if (!tenantCode) return 'tenant'
  return username ? 'password' : 'username'
}
```

Add `tenantSelectRef`, `usernameInputRef`, and `passwordInputRef`. After `await loadTenantOptions()` and `nextTick()`, focus the resolved control. Add `autocomplete="username"` to username and `autocomplete="current-password"` to password. Tenant Enter after selection focuses username, username Enter focuses password, and password Enter calls `handleLogin`; keep button click as the submit route. Continue rewriting parsed legacy remembered data to the sanitized two-field object.

- [ ] **Step 4: Verify and commit**

Run from `../front`: `node --test tests/login-focus.test.mjs tests/tenant-login.test.mjs`

Expected: PASS for storage sanitization, autocomplete, async initial focus, and Enter order.

```bash
git add src/views/login/index.vue src/utils/remembered-login.js src/utils/login-focus.js tests/login-focus.test.mjs tests/tenant-login.test.mjs
git commit -m "fix: use native password management and login focus flow"
```

### Task 4: Preserve tenant-aware deep links across 401

**Files:**
- Modify: `../front/src/utils/auth.js`
- Modify: `../front/src/stores/user.js`
- Modify: `../front/src/utils/tenant-login.js`
- Modify: `../front/src/utils/request.js`
- Modify: `../front/src/views/login/index.vue`
- Modify: `../front/tests/tenant-login.test.mjs`
- Modify: `../front/e2e-test.cjs`

- [ ] **Step 1: Write failing redirect-priority tests**

```javascript
test('tenant precedence is explicit, redirect, remembered, platform, first', () => {
  assert.equal(selectInitialTenant({ requestedTenant: 'a', redirectTenant: 'b', rememberedTenant: 'c', options }).tenantCode, 'a')
  assert.equal(selectInitialTenant({ redirectTenant: 'b', rememberedTenant: 'c', options }).tenantCode, 'b')
  assert.equal(selectInitialTenant({ rememberedTenant: 'c', options }).tenantCode, 'c')
})

test('401 location keeps full path and active tenant code', () => {
  assert.deepEqual(buildLoginLocation({ fullPath: '/workorder/detail/9?x=1#timeline', query: {} }, 'acme'), {
    path: '/login',
    query: { tenantCode: 'acme', redirect: '/workorder/detail/9?x=1#timeline' },
  })
})
```

Update E2E expectations to assert `loginUrl.searchParams.get('tenantCode')` and exact final path/query/hash.

- [ ] **Step 2: Run and confirm RED**

Run from `../front`: `node --test tests/tenant-login.test.mjs tests/e2e-contract.test.mjs`

Expected: FAIL because current code writes `tenant`, has no redirect-tenant precedence, and does not store `activeTenantCode`.

- [ ] **Step 3: Standardize the query and snapshot before reset**

Extend `normalizeAuthContext` and the Pinia store with string/null `activeTenantCode`; set and clear it beside active ID/name. Implement:

```javascript
export function tenantFromRedirect(value) {
  const safe = resolveSafeRedirect(value, '')
  if (!safe) return ''
  return new URL(safe, 'http://local').searchParams.get('tenantCode') || ''
}

export function buildLoginLocation(route, activeTenantCode = '') {
  const tenantCode = normalizeTenantCode(activeTenantCode)
    || normalizeTenantCode(firstQueryString(route?.query?.tenantCode))
  return { path: '/login', query: { tenantCode, redirect: route?.fullPath || '/' } }
}
```

Change login selection to `requestedTenant: firstQueryString(route.query.tenantCode)` plus `redirectTenant: tenantFromRedirect(route.query.redirect)`. Replace/write/watch only `tenantCode`. In `handle401`, capture `route.fullPath` and `userStore.activeTenantCode`, build the location, then call `resetState()`. At the start of the router guard capture `const tenantCodeSnapshot = userStore.activeTenantCode`; every guard catch builds its login location with that snapshot before clearing state, so an interceptor-triggered reset cannot erase the fallback tenant.

- [ ] **Step 4: Verify and commit**

Run from `../front`: `node --test tests/tenant-login.test.mjs tests/e2e-contract.test.mjs`

Expected: PASS for priority, safe redirects, one query name, and pre-reset snapshot.

```bash
git add src/utils/auth.js src/stores/user.js src/utils/tenant-login.js src/utils/request.js src/views/login/index.vue tests/tenant-login.test.mjs e2e-test.cjs tests/e2e-contract.test.mjs
git commit -m "fix: preserve tenant deep links across authentication loss"
```

### Task 5: Build the backend navigation tree by post-order pruning

**Files:**
- Modify: `src/main/java/top/hetao/shiyuanticketmp/menu/service/MenuService.java`
- Test: `src/test/java/top/hetao/shiyuanticketmp/menu/service/MenuServiceNavigationTreeTest.java`

- [ ] **Step 1: Write failing tree tests**

```java
@Test
void navigationIsPostOrderPrunedButAdminTreeIsComplete() {
    List<SysMenu> menus = List.of(
        menu(1L, 0L, "DIR", null, null),
        menu(2L, 1L, "DIR", null, null),
        menu(3L, 2L, "MENU", "/settings", "settings:view"),
        menu(4L, 2L, "BUTTON", null, "settings:update"),
        menu(5L, 0L, "DIR", null, null));
    when(service.list(any())).thenReturn(menus);
    List<Map<String, Object>> userTree = service.getMenuTree(List.of("settings:view"));
    assertThat(ids(userTree)).containsExactly(1L, 2L, 3L);
    assertThat(find(userTree, 3L).get("children")).isEqualTo(List.of());
    assertThat(ids(service.getFullTree())).contains(1L, 2L, 3L, 4L, 5L);
}
```

Add cases for denied MENU, DIR whose only child is BUTTON, visible deep nesting, and a DIR permission code that must not hide an accessible descendant.

- [ ] **Step 2: Run and confirm RED**

Run: `mvn -Dtest=MenuServiceNavigationTreeTest test`

Expected: FAIL because current filtering is pre-order, includes empty DIR, and omits empty `children`.

- [ ] **Step 3: Separate navigation pruning from the full admin tree**

```java
private Optional<Map<String, Object>> buildNavigationNode(
        SysMenu menu, Map<Long, List<SysMenu>> byParent, Set<String> permissions) {
    if ("BUTTON".equals(menu.getMenuType())) return Optional.empty();
    List<Map<String, Object>> children = byParent.getOrDefault(menu.getId(), List.of()).stream()
            .map(child -> buildNavigationNode(child, byParent, permissions))
            .flatMap(Optional::stream).toList();
    if ("MENU".equals(menu.getMenuType()) && !hasPermission(menu, permissions)) return Optional.empty();
    if ("DIR".equals(menu.getMenuType()) && children.isEmpty()) return Optional.empty();
    Map<String, Object> node = toNode(menu);
    node.put("children", children);
    return Optional.of(node);
}
```

Index visible menus by parent while preserving `sortOrder`; call the method only for the user tree. `getFullTree()` uses a separate recursive builder that keeps DIR/MENU/BUTTON and always sets an array `children`, without applying user permissions or empty-DIR pruning.

- [ ] **Step 4: Verify and commit**

Run: `mvn -Dtest=MenuServiceNavigationTreeTest test`

Expected: PASS for deep post-order pruning and unchanged admin inventory.

```bash
git add src/main/java/top/hetao/shiyuanticketmp/menu/service/MenuService.java src/test/java/top/hetao/shiyuanticketmp/menu/service/MenuServiceNavigationTreeTest.java
git commit -m "fix: prune user menus after processing descendants"
```

### Task 6: Normalize menus recursively against registered Vue routes

**Files:**
- Create: `../front/src/utils/menu-navigation.js`
- Create: `../front/tests/menu-navigation.test.mjs`
- Modify: `../front/src/stores/user.js`

- [ ] **Step 1: Write failing pure tests**

```javascript
test('normalizer removes buttons, empty dirs, duplicates, blanks and unknown routes', () => {
  const result = normalizeMenuTree(rawMenus, ['/workorder/list', '/settings'])
  assert.deepEqual(flattenPaths(result), ['/workorder/list', '/settings'])
  assert.ok(result.every(node => Array.isArray(node.children)))
  assert.equal(JSON.stringify(result).includes('guessed/from/menuCode'), false)
})

test('first route is depth-first and registered', () => {
  assert.equal(firstAccessiblePath(normalizedMenus), '/settings')
  assert.equal(firstAccessiblePath([]), '/404')
})
```

- [ ] **Step 2: Run and confirm RED**

Run from `../front`: `node --test tests/menu-navigation.test.mjs`

Expected: FAIL because `menu-navigation.js` is absent.

- [ ] **Step 3: Implement a defensive recursive normalizer**

```javascript
export function normalizeMenuTree(rawMenus, registeredPaths) {
  const allowed = new Set(registeredPaths)
  const used = new Set()
  const visit = (node) => {
    if (!node || node.menuType === 'BUTTON') return null
    const children = (Array.isArray(node.children) ? node.children : []).map(visit).filter(Boolean)
    if (node.menuType === 'DIR') return children.length ? { ...node, path: '', children } : null
    const path = typeof node.path === 'string' ? node.path.trim() : ''
    if (node.menuType !== 'MENU' || !path || !allowed.has(path) || used.has(path)) return null
    used.add(path)
    return { ...node, path, children }
  }
  return (Array.isArray(rawMenus) ? rawMenus : []).map(visit).filter(Boolean)
}

export function firstAccessiblePath(tree, fallback = '/404') {
  for (const node of tree) {
    if (node.menuType === 'MENU' && node.path) return node.path
    const child = firstAccessiblePath(node.children || [], '')
    if (child) return child
  }
  return fallback
}
```

Keep raw API data in `userStore.menuTree`; callers pass `router.getRoutes().map(route => route.path)` so the route registry cannot drift or create an import cycle.

- [ ] **Step 4: Verify and commit**

Run from `../front`: `node --test tests/menu-navigation.test.mjs`

Expected: PASS for arbitrary depth, duplicate first-wins, unknown/blank path removal, and no menuCode guessing.

```bash
git add src/utils/menu-navigation.js src/stores/user.js tests/menu-navigation.test.mjs
git commit -m "fix: normalize navigation against registered routes"
```

### Task 7: Render recursive sidebar menus and route `/` to the first accessible page

**Files:**
- Create: `../front/src/layout/components/SidebarMenuItem.vue`
- Modify: `../front/src/layout/index.vue`
- Modify: `../front/src/router/index.js`
- Modify: `../front/tests/menu-navigation.test.mjs`
- Modify: `../front/e2e-test.cjs`

- [ ] **Step 1: Add failing source/E2E tests**

Require the recursive component to call itself for `children`; require router root handling to use `firstAccessiblePath`; forbid `redirect: '/workorder/list'`, `menuCode.replace`, and two-level `v-for="child in menu.children"`. Add an E2E account fixture/mocked menu where `/settings` is the first authorized route and assert visiting `/` lands on `/settings`.

Run from `../front`: `node --test tests/menu-navigation.test.mjs tests/e2e-contract.test.mjs`

Expected: FAIL because the layout is two-level and root is hard-coded.

- [ ] **Step 2: Implement the recursive item**

```vue
<template>
  <el-sub-menu v-if="item.children.length" :index="`dir:${String(item.id)}`">
    <template #title><span>{{ item.menuName }}</span></template>
    <SidebarMenuItem v-for="child in item.children" :key="String(child.id)" :item="child" />
  </el-sub-menu>
  <el-menu-item v-else :index="item.path"><template #title>{{ item.menuName }}</template></el-menu-item>
</template>

<script setup>
defineProps({ item: { type: Object, required: true } })
</script>
```

Preserve icon rendering by passing a resolved icon component prop or rendering it inside this component. In layout, compute `normalizeMenuTree(userStore.menuTree, router.getRoutes().map(r => r.path))`; remove duplicate-path mutation and synthetic tenant-admin injection, except the explicit global-admin/no-active special menu already required for tenant selection.

- [ ] **Step 3: Resolve root after identity/menu loading**

Remove the static Layout redirect. In the guard, after loading identity and normalized menus:

```javascript
if (to.path === '/') {
  next({ path: firstAccessiblePath(normalizedMenus), replace: true })
  return
}
```

Apply the same rule when `routerLoaded` is already true. Keep global/no-active administrators on `/system/tenant`; retain `canAccessTargetRoute` for direct navigation.

- [ ] **Step 4: Verify and commit**

Run from `../front`: `node --test tests/menu-navigation.test.mjs tests/e2e-contract.test.mjs`

Expected: PASS for recursive source contracts and first-route selection.

Run from `../front` with apps started: `node e2e-test.cjs`

Expected: the menu/deep-link smoke section passes; unavailable business credentials remain skipped.

```bash
git add src/layout/components/SidebarMenuItem.vue src/layout/index.vue src/router/index.js tests/menu-navigation.test.mjs tests/e2e-contract.test.mjs e2e-test.cjs
git commit -m "fix: render recursive menus and choose accessible home"
```

### Task 8: Create the unified keyboard-accessible `OpaqueId`

**Files:**
- Create: `../front/src/components/OpaqueId.vue`
- Create: `../front/src/utils/opaque-id.js`
- Create: `../front/tests/opaque-id.test.mjs`

- [ ] **Step 1: Write failing value/source tests**

```javascript
test('opaque ids stringify without numeric conversion', () => {
  assert.deepEqual(normalizeOpaqueId(1980000000000000001n), {
    empty: false, text: '1980000000000000001',
  })
  assert.deepEqual(normalizeOpaqueId(null), { empty: true, text: '-' })
})
```

Source assertions require tooltip, `tabindex="0"`, click, Enter and Space copy handlers, `white-space: nowrap`, `overflow: hidden`, `text-overflow: ellipsis`, and tabular monospace digits.

- [ ] **Step 2: Run and confirm RED**

Run from `../front`: `node --test tests/opaque-id.test.mjs`

Expected: FAIL because the utility/component are absent.

- [ ] **Step 3: Implement the component**

```javascript
export function normalizeOpaqueId(value, placeholder = '-') {
  if (value === null || value === undefined || value === '') return { empty: true, text: placeholder }
  return { empty: false, text: String(value) }
}
```

`OpaqueId.vue` computes that result, renders the placeholder without copy affordance, otherwise wraps the complete text in `el-tooltip`, and calls `navigator.clipboard.writeText(normalized.text)` on click, Enter, or Space. Use `role="button"`, `tabindex="0"`, `aria-label="复制 ID {fullValue}"`, and stop Space default scrolling. Apply a scoped single-line ellipsis style with `font-family: ui-monospace, SFMono-Regular, Consolas, monospace` and `font-variant-numeric: tabular-nums`.

- [ ] **Step 4: Verify and commit**

Run from `../front`: `node --test tests/opaque-id.test.mjs`

Expected: PASS for string preservation, placeholder, keyboard/click copy, tooltip, and CSS contracts.

```bash
git add src/components/OpaqueId.vue src/utils/opaque-id.js tests/opaque-id.test.mjs
git commit -m "feat: add accessible opaque id display"
```

### Task 9: Replace work-order, user, tenant, and role ID displays

**Files:**
- Modify: `../front/src/views/workorder/list.vue`
- Modify: `../front/src/views/workorder/detail.vue`
- Modify: `../front/src/views/admin/users.vue`
- Modify: `../front/src/views/admin/tenants.vue`
- Modify: `../front/src/views/admin/roles.vue`
- Modify: `../front/tests/opaque-id.test.mjs`

- [ ] **Step 1: Add failing source inventory tests**

Read all five files and assert each imports `OpaqueId`; assert ID columns use slot templates rather than `prop="id"` direct rendering; forbid `Number(...id...)`, `parseInt(...id...)`, and business-ID array conversion. Keep existing `Number(res.data?.total)` pagination conversions allowed.

- [ ] **Step 2: Run and confirm RED**

Run from `../front`: `node --test tests/opaque-id.test.mjs`

Expected: FAIL because the views do not import/use `OpaqueId`.

- [ ] **Step 3: Replace the exact displays**

Use a uniform table minimum width and slot:

```vue
<el-table-column label="工单ID" min-width="180" class-name="opaque-id-column">
  <template #default="{ row }"><OpaqueId :value="row.id" /></template>
</el-table-column>
```

Apply it to work-order ID/list/detail, user ID, external user ID, tenant ID fallback, tenant-table ID, and role ID. Use `OpaqueId` for assignee-ID fallback without altering name/role precedence. Remove only the work-order-ID branch from the local manual copy markup; retain tracking-number/address copy behavior. Do not change IDs passed to routes, APIs, selections, `node-key`, or role arrays.

- [ ] **Step 4: Verify and commit**

Run from `../front`: `node --test tests/opaque-id.test.mjs`

Expected: PASS for all five view inventories and no precision conversion.

```bash
git add src/views/workorder/list.vue src/views/workorder/detail.vue src/views/admin/users.vue src/views/admin/tenants.vue src/views/admin/roles.vue tests/opaque-id.test.mjs
git commit -m "feat: standardize primary id displays"
```

### Task 10: Replace audit, dead-letter, event, and associated business IDs

**Files:**
- Modify: `../front/src/views/audit/logs.vue`
- Modify: `../front/src/views/admin/deadletters.vue`
- Modify: `../front/tests/opaque-id.test.mjs`
- Modify: `../front/e2e-test.cjs`

- [ ] **Step 1: Add failing source/E2E tests**

Require `OpaqueId` for audit `id`/`bizId` and dead-letter `id`/`eventId`; require minimum-width single-line columns and forbid numeric ID conversion. Add Playwright checks that an ID cell has full tooltip text, responds to Enter copy, and its row height does not grow for a 19-digit ID.

- [ ] **Step 2: Run and confirm RED**

Run from `../front`: `node --test tests/opaque-id.test.mjs tests/e2e-contract.test.mjs`

Expected: FAIL because these four columns still render raw props.

- [ ] **Step 3: Replace the four columns**

```vue
<el-table-column label="业务ID" min-width="180" class-name="opaque-id-column">
  <template #default="{ row }"><OpaqueId :value="row.bizId" /></template>
</el-table-column>
```

Repeat for audit ID, dead-letter ID, and event ID. Keep `pagination.total = Number(...)`; do not convert `row.id`, `row.bizId`, `row.eventId`, or action payloads.

- [ ] **Step 4: Verify and commit**

Run from `../front`: `node --test tests/opaque-id.test.mjs tests/e2e-contract.test.mjs`

Expected: PASS for inventory and precision guards.

Run from `../front` with apps started: `node e2e-test.cjs`

Expected: ID tooltip/copy/single-row checks pass.

```bash
git add src/views/audit/logs.vue src/views/admin/deadletters.vue tests/opaque-id.test.mjs tests/e2e-contract.test.mjs e2e-test.cjs
git commit -m "feat: standardize event and business id displays"
```

### Task 11: Final verification and auth API documentation sync

**Files:**
- Modify in Apifox project `8260787`: `POST /api/auth/login`
- Modify in Apifox project `8260787`: `GET /api/auth/me`
- Modify in Apifox project `8260787`: `POST /api/auth/switch-tenant`
- Verify only: all files above

- [ ] **Step 1: Run backend focused tests and compile**

Run from `backend/`:

```bash
mvn -Dtest=AuthTenantServiceContextTest,AuthTenantServiceLegacySessionTest,TenantInterceptorInvalidSessionTest,MenuServiceNavigationTreeTest test
mvn compile
```

Expected: all focused tests pass and compile ends with `BUILD SUCCESS`.

- [ ] **Step 2: Run frontend deterministic tests and build**

Run from `front/`:

```bash
node --test tests/login-focus.test.mjs tests/tenant-login.test.mjs tests/menu-navigation.test.mjs tests/opaque-id.test.mjs tests/e2e-contract.test.mjs
npm run build
```

Expected: Node reports all tests passed; Vite exits 0 and writes `dist/`.

- [ ] **Step 3: Run the integrated browser flow**

With backend and frontend already started, run from `front/`: `node e2e-test.cjs`.

Expected: async tenant loading, remembered account focus, Enter flow, 401 exact deep-link restoration, recursive menu/root selection, and ID tooltip/copy checks pass; optional business-account coverage skips only when its three environment variables are absent.

- [ ] **Step 4: Synchronize and re-read API contracts**

Use the Apifox MCP for project `8260787` to add `activeTenantCode` to the three auth responses and document corrupt/disabled legacy-session HTTP 401 behavior. Read the endpoints back and confirm there is no local `API.md`.

- [ ] **Step 5: Inspect repository state**

Run separately in `backend/` and `front/`: `git diff --check` and `git status --short`.

Expected: no whitespace errors; no password-bearing storage fixture, secret, `target/`, `dist/`, screenshot, or report is staged. Do not create an empty commit.
