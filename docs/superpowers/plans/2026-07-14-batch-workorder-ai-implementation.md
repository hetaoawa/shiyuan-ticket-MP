# Batch Work Order AI Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add one-call batch AI parsing, atomic and database-idempotent same-type batch creation, retryable per-order common attachments, and one shared single/batch creation editor.

**Architecture:** Keep `POST /api/workorders` compatible, but extract its prepare/insert path for reuse by a transactional batch service. Claim `(tenant_id, requester_id, idempotency_key)` in MySQL before inserts, store the canonical request hash and ordered IDs in the same transaction, and run existing listeners only after commit. Extract AI policy from the controller, validate the one-response batch schema, and make both frontend entry points render one stateful editor whose attachment queue is keyed by `(workOrderId, localFileId)`.

**Tech Stack:** Java 17, Spring Boot 3.0.2, MyBatis-Plus 3.5.5, MySQL, Redis, JUnit 5/Mockito, Vue 3, Vite 8, Element Plus, Axios, Playwright.

---

## Contract invariants

- Batch AI accepts nonblank text of at most 2,000 characters and makes exactly one model HTTP call; a result contains 1-20 valid items.
- Batch creation accepts 1-20 items of one top-level `WorkOrderType`; it rejects `tenantId`, `submitterId`, `senderStaffId`, and `conversationId` even when their values are null.
- Neither station-side editor mode renders or sends external identity/context fields. The existing single API keeps backward-compatible fields for non-editor integration callers.
- `trackingNo`/`title` are required and limited to 50/200 characters; `targetAddress` is limited to 500 and required for `CHANGE_ADDRESS`; priority is 1-3; tracking numbers are unique after trim and case folding.
- The current authenticated user and `TenantContext.requireTenantId()` are the only batch identity sources. AI/S3 calls never run inside the creation transaction.
- A successful replay returns the original ordered string IDs and publishes no new event. A key reused with another canonical request returns HTTP 409. Any item failure rolls back the batch row, every work order, and every AFTER_COMMIT effect.

### Task 1: Add the V24 database idempotency record

**Files:**
- Create: `src/main/resources/db/migration/V24__create_work_order_batch_request.sql`
- Create: `src/main/java/top/hetao/shiyuanticketmp/workorder/batch/entity/WorkOrderBatchRequest.java`
- Create: `src/main/java/top/hetao/shiyuanticketmp/workorder/batch/mapper/WorkOrderBatchRequestMapper.java`
- Test: `src/test/java/top/hetao/shiyuanticketmp/workorder/batch/mapper/WorkOrderBatchRequestSqlTest.java`

- [ ] **Step 1: Write the failing migration contract test**

```java
@Test
void migrationDefinesTenantScopedUniqueClaimAndOrderedIds() throws Exception {
    String sql = Files.readString(Path.of("src/main/resources/db/migration/V24__create_work_order_batch_request.sql"));
    assertThat(sql).contains("UNIQUE KEY uk_work_order_batch_idempotency (tenant_id, requester_id, idempotency_key)");
    assertThat(sql).contains("request_hash CHAR(64) NOT NULL");
    assertThat(sql).contains("work_order_ids_json JSON NULL");
}
```

- [ ] **Step 2: Run it and confirm RED**

Run: `mvn -Dtest=WorkOrderBatchRequestSqlTest test`

Expected: FAIL because `V24__create_work_order_batch_request.sql` does not exist.

- [ ] **Step 3: Add the table and atomic claim mapper**

```sql
CREATE TABLE work_order_batch_request (
    id BIGINT NOT NULL,
    tenant_id BIGINT NOT NULL,
    requester_id BIGINT NOT NULL,
    idempotency_key VARCHAR(64) NOT NULL,
    request_hash CHAR(64) NOT NULL,
    work_order_ids_json JSON NULL,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_work_order_batch_idempotency (tenant_id, requester_id, idempotency_key)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
```

Use these mapper signatures with `INSERT IGNORE`, an exact-key `SELECT ... FOR UPDATE`, and an ordered-ID completion update:

```java
int claim(long id, long tenantId, long requesterId, String key, String hash);
WorkOrderBatchRequest selectForUpdate(long tenantId, long requesterId, String key);
int complete(long id, String json);
```

Supply a Snowflake ID before the claim. A losing concurrent transaction waits on the unique index, then reads the committed owner row; do not implement Redis-only idempotency.

- [ ] **Step 4: Verify GREEN and commit**

Run: `mvn -Dtest=WorkOrderBatchRequestSqlTest test`

Expected: PASS (1 test, 0 failures).

```bash
git add src/main/resources/db/migration/V24__create_work_order_batch_request.sql src/main/java/top/hetao/shiyuanticketmp/workorder/batch src/test/java/top/hetao/shiyuanticketmp/workorder/batch/mapper/WorkOrderBatchRequestSqlTest.java
git commit -m "feat: add work order batch idempotency record"
```

### Task 2: Define and validate the batch creation contract

**Files:**
- Create: `src/main/java/top/hetao/shiyuanticketmp/workorder/batch/dto/BatchCreateWorkOrderRequest.java`
- Create: `src/main/java/top/hetao/shiyuanticketmp/workorder/batch/dto/BatchCreateWorkOrderResult.java`
- Create: `src/main/java/top/hetao/shiyuanticketmp/workorder/batch/service/BatchWorkOrderValidator.java`
- Test: `src/test/java/top/hetao/shiyuanticketmp/workorder/batch/service/BatchWorkOrderValidatorTest.java`

- [ ] **Step 1: Write table-driven failing tests**

```java
@ParameterizedTest
@MethodSource("invalidRequests")
void rejectsInvalidBatch(BatchCreateWorkOrderRequest request, String message) {
    assertThatThrownBy(() -> validator.validateAndNormalize(request))
            .isInstanceOf(WorkOrderException.class).hasMessageContaining(message);
}

static Stream<Arguments> invalidRequests() {
    return Stream.of(
        Arguments.of(request(List.of()), "1-20"),
        Arguments.of(request(items(21)), "1-20"),
        Arguments.of(request(item("yt1"), item(" YT1 ")), "重复"),
        Arguments.of(changeAddressItem("YT1", ""), "目标地址"),
        Arguments.of(priorityItem(4), "优先级")
    );
}
```

Also deserialize JSON containing each forbidden identity field and assert HTTP request-body parsing fails. Implement setters such as:

```java
@JsonSetter("senderStaffId")
public void rejectSenderStaffId(Object ignored) {
    throw new IllegalArgumentException("批量创建不接受 senderStaffId");
}
```

- [ ] **Step 2: Run and confirm RED**

Run: `mvn -Dtest=BatchWorkOrderValidatorTest test`

Expected: FAIL because the DTO and validator are absent.

- [ ] **Step 3: Implement normalization and stable hashing input**

Return an immutable normalized command containing parsed `WorkOrderType`, trimmed fields, default priority 2, and original request order. Build the SHA-256 input with an `ObjectMapper` over a record, never `Map.toString()`:

```java
public record CanonicalItem(String clientItemId, String trackingNo, String title,
                            String description, String targetAddress, int priority) {}
public record CanonicalBatch(WorkOrderType type, List<CanonicalItem> items) {}

public record BatchCreateWorkOrderResult(String batchId, int createdCount, boolean replayed,
                                         List<Item> items) {
    public record Item(String clientItemId, String id, String trackingNo) {}
}

public String hash(CanonicalBatch batch) {
    try {
        byte[] json = objectMapper.writeValueAsBytes(batch);
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(json));
    } catch (JsonProcessingException | NoSuchAlgorithmException e) {
        throw new IllegalStateException("无法规范化批量请求", e);
    }
}
```

- [ ] **Step 4: Verify and commit**

Run: `mvn -Dtest=BatchWorkOrderValidatorTest test`

Expected: PASS for empty/21/duplicate/length/type/priority/address/forbidden-identity cases.

```bash
git add src/main/java/top/hetao/shiyuanticketmp/workorder/batch src/test/java/top/hetao/shiyuanticketmp/workorder/batch/service/BatchWorkOrderValidatorTest.java
git commit -m "feat: validate canonical work order batches"
```

### Task 3: Share the single-order prepare and insert path

**Files:**
- Create: `src/main/java/top/hetao/shiyuanticketmp/workorder/service/WorkOrderCreationService.java`
- Modify: `src/main/java/top/hetao/shiyuanticketmp/workorder/service/impl/WorkOrderServiceImpl.java`
- Test: `src/test/java/top/hetao/shiyuanticketmp/workorder/service/WorkOrderCreationServiceTest.java`

- [ ] **Step 1: Pin current single-create behavior with a failing extraction test**

```java
@Test
void preparedInsertSetsPendingAndPublishesCreateEvent() {
    WorkOrder order = new WorkOrder();
    order.setTitle("拦截工单 - YT1");
    order.setTrackingNo("YT1");
    WorkOrder created = service.prepareAndInsert(order, 100L, 9L);
    assertThat(created.getTenantId()).isEqualTo(100L);
    assertThat(created.getSubmitterId()).isEqualTo(9L);
    assertThat(created.getStatus()).isEqualTo(WorkOrderStatus.PENDING);
    verify(mapper).insert(created);
    verify(events).publishEvent(isA(WorkOrderStateChangedEvent.class));
}
```

- [ ] **Step 2: Run and confirm RED**

Run: `mvn -Dtest=WorkOrderCreationServiceTest test`

Expected: FAIL because `WorkOrderCreationService` is absent.

- [ ] **Step 3: Extract without changing the public single endpoint**

Expose three non-transactional operations used only inside transactional callers:

```java
public void validateSubmitter(long tenantId, long submitterId) {
    SysUser tenantSubmitter = userMapper.selectById(submitterId);
    if (tenantSubmitter != null && Long.valueOf(tenantId).equals(tenantSubmitter.getTenantId())) return;
    Long principalId = currentActorId();
    if (principalId == null || principalId != submitterId) {
        throw new WorkOrderException("工单提交人不属于当前租户");
    }
    try (TenantContext.Scope ignored = TenantContext.useInternalBypass()) {
        SysUser principal = userMapper.selectByIdIgnoreTenant(principalId);
        List<String> roles = roleMapper.selectRoleCodesByUserId(principalId);
        if (principal == null || !Long.valueOf(0L).equals(principal.getTenantId())
                || !roles.contains("GLOBAL_SYSTEM_ADMIN")) {
            throw new WorkOrderException("工单提交人不属于当前租户");
        }
    }
}

private Long currentActorId() {
    if (!StpUtil.isLogin()) return null;
    try {
        return Long.valueOf(StpUtil.getLoginIdAsString());
    } catch (NumberFormatException ex) {
        throw new WorkOrderException("登录用户 ID 格式错误");
    }
}

public WorkOrder prepare(WorkOrder order, long tenantId, long submitterId) {
    order.setTenantId(tenantId);
    order.setSubmitterId(submitterId);
    order.setCreatedViaWebhook(Boolean.TRUE.equals(order.getCreatedViaWebhook()));
    order.setStatus(WorkOrderStatus.PENDING);
    if (order.getType() == null) order.setType(typeResolver.resolve(order.getTitle(), order.getDescription()));
    return order;
}

public WorkOrder insertPrepared(WorkOrder order, long actorId) {
    mapper.insert(order);
    eventPublisher.publishEvent(new WorkOrderStateChangedEvent(
        this, order.getTenantId(), order, null, "CREATE", actorId, Map.of()));
    return order;
}
```

Keep `WorkOrderServiceImpl.create()` transactional: require and lock the tenant once, validate the existing single-order submitter semantics, then call `prepare()` and `insertPrepared()`. Do not call another method on `this` to obtain a transaction.

- [ ] **Step 4: Verify and commit**

Run: `mvn -Dtest=WorkOrderCreationServiceTest test`

Expected: PASS and exactly one insert/event.

```bash
git add src/main/java/top/hetao/shiyuanticketmp/workorder/service src/test/java/top/hetao/shiyuanticketmp/workorder/service/WorkOrderCreationServiceTest.java
git commit -m "refactor: share work order creation primitive"
```

### Task 4: Implement one-transaction batch creation and replay

**Files:**
- Create: `src/main/java/top/hetao/shiyuanticketmp/workorder/batch/exception/IdempotencyConflictException.java`
- Create: `src/main/java/top/hetao/shiyuanticketmp/workorder/batch/service/BatchWorkOrderService.java`
- Test: `src/test/java/top/hetao/shiyuanticketmp/workorder/batch/service/BatchWorkOrderServiceTest.java`
- Test: `src/test/java/top/hetao/shiyuanticketmp/workorder/batch/service/BatchWorkOrderTransactionTest.java`
- Modify: `pom.xml` (add H2 with test scope for the transaction test)

- [ ] **Step 1: Write failing service and transaction tests**

```java
@Test
void replayReturnsOriginalIdsWithoutInsertingOrPublishing() {
    when(batchMapper.claim(anyLong(), eq(100L), eq(9L), eq(KEY), eq(HASH))).thenReturn(0);
    when(batchMapper.selectForUpdate(100L, 9L, KEY)).thenReturn(completed(HASH, "[\"91\",\"92\"]"));
    BatchCreateWorkOrderResult result = service.create(request(), KEY, 9L);
    assertThat(result.replayed()).isTrue();
    assertThat(result.items()).extracting(BatchCreateWorkOrderResult.Item::id).containsExactly("91", "92");
    verify(creation, never()).insertPrepared(any(), anyLong());
}

@Test
void failureOnNthInsertRollsBackRowsAndAfterCommitListener() {
    assertThatThrownBy(() -> service.create(threeItemsWithThirdViolatingDbConstraint(), KEY, 9L));
    assertThat(jdbc.queryForObject("select count(*) from work_order", Integer.class)).isZero();
    assertThat(jdbc.queryForObject("select count(*) from work_order_batch_request", Integer.class)).isZero();
    assertThat(afterCommitEvents.get()).isZero();
}
```

Add concurrent same-key coverage with two executor threads and a barrier; assert one batch row, one ordered ID set, and one set of work orders.

- [ ] **Step 2: Run and confirm RED**

Run: `mvn -Dtest=BatchWorkOrderServiceTest,BatchWorkOrderTransactionTest test`

Expected: FAIL because the service and transaction schema fixture are absent.

- [ ] **Step 3: Implement validate-before-lock, claim-before-insert**

```java
@Transactional
public BatchCreateWorkOrderResult create(BatchCreateWorkOrderRequest request, String key, long requesterId) {
    CanonicalBatch batch = validator.validateAndNormalize(request);
    String hash = validator.hash(batch);
    long tenantId = TenantContext.requireTenantId();
    tenantLifecycleGuard.lockWritableTenant(tenantId);
    creation.validateSubmitter(tenantId, requesterId);
    long batchId = IdWorker.getId();
    int claimed = batchMapper.claim(batchId, tenantId, requesterId, key, hash);
    WorkOrderBatchRequest row = batchMapper.selectForUpdate(tenantId, requesterId, key);
    if (!hash.equals(row.getRequestHash())) throw new IdempotencyConflictException();
    if (claimed == 0) return replay(row, batch);
    List<Long> ids = new ArrayList<>();
    for (CanonicalItem item : batch.items()) {
        WorkOrder order = creation.prepare(toEntity(item, batch.type()), tenantId, requesterId);
        ids.add(creation.insertPrepared(order, requesterId).getId());
    }
    batchMapper.complete(batchId, objectMapper.writeValueAsString(ids));
    return created(batchId, batch, ids);
}
```

The replay branch must require non-null stored IDs; an incomplete row is an invariant violation and returns 500. Serialize IDs as decimal strings in API result records.

- [ ] **Step 4: Verify and commit**

Run: `mvn -Dtest=BatchWorkOrderServiceTest,BatchWorkOrderTransactionTest test`

Expected: PASS, including 20 distinct IDs, Nth-item rollback, same/different request replay, and concurrent claim.

```bash
git add pom.xml src/main/java/top/hetao/shiyuanticketmp/workorder/batch src/test/java/top/hetao/shiyuanticketmp/workorder/batch
git commit -m "feat: create idempotent work order batches"
```

### Task 5: Expose the authenticated batch API and 409 semantics

**Files:**
- Modify: `src/main/java/top/hetao/shiyuanticketmp/workorder/controller/WorkOrderController.java`
- Modify: `src/main/java/top/hetao/shiyuanticketmp/common/exception/GlobalExceptionHandler.java`
- Test: `src/test/java/top/hetao/shiyuanticketmp/workorder/controller/WorkOrderBatchControllerTest.java`

- [ ] **Step 1: Write failing MockMvc tests** for missing/7/65-character keys (400), valid request (ordered string IDs), forbidden identities (400), and `IdempotencyConflictException` (409).

```java
mockMvc.perform(post("/api/workorders/batch")
        .header("Idempotency-Key", "01234567")
        .contentType(APPLICATION_JSON).content(validJson()))
    .andExpect(status().isOk())
    .andExpect(jsonPath("$.data.items[0].id").value("1980000000000000001"));
```

- [ ] **Step 2: Run and confirm RED**

Run: `mvn -Dtest=WorkOrderBatchControllerTest test`

Expected: FAIL with 404 for `/api/workorders/batch`.

- [ ] **Step 3: Add the endpoint and conflict handler**

```java
@PostMapping("/batch")
@SaCheckPermission("workorder:create")
public Map<String, Object> createBatch(
        @RequestHeader("Idempotency-Key") String key,
        @RequestBody BatchCreateWorkOrderRequest request) {
    if (!key.matches("^[A-Za-z0-9_-]{8,64}$")) throw new WorkOrderException("Idempotency-Key 长度必须为 8-64");
    long requesterId = Long.parseLong(StpUtil.getLoginIdAsString());
    return Map.of("code", 200, "message", "批量创建成功", "data", batchService.create(request, key, requesterId));
}
```

Map `IdempotencyConflictException` to HTTP/body code 409. Keep `POST /api/workorders` unchanged.

- [ ] **Step 4: Verify and commit**

Run: `mvn -Dtest=WorkOrderBatchControllerTest test`

Expected: PASS for 200/400/409 contracts.

```bash
git add src/main/java/top/hetao/shiyuanticketmp/workorder/controller/WorkOrderController.java src/main/java/top/hetao/shiyuanticketmp/common/exception/GlobalExceptionHandler.java src/test/java/top/hetao/shiyuanticketmp/workorder/controller/WorkOrderBatchControllerTest.java
git commit -m "feat: expose batch work order creation api"
```

### Task 6: Extract tenant-aware AI cache/rate/rejection policy

**Files:**
- Create: `src/main/java/top/hetao/shiyuanticketmp/ai/policy/AiParseMode.java`
- Create: `src/main/java/top/hetao/shiyuanticketmp/ai/policy/AiParsePolicyStore.java`
- Create: `src/main/java/top/hetao/shiyuanticketmp/ai/policy/RedisAiParsePolicyStore.java`
- Create: `src/main/java/top/hetao/shiyuanticketmp/ai/policy/AiParsePolicyService.java`
- Modify: `src/main/java/top/hetao/shiyuanticketmp/ai/AiParseController.java`
- Test: `src/test/java/top/hetao/shiyuanticketmp/ai/policy/AiParsePolicyServiceTest.java`

- [ ] **Step 1: Write failing policy tests** for success-cache hit consuming zero quota, cached 418 incrementing one rejection, fifth rejection creating a 600-second block, tenant/user/mode/schema key separation, and cache miss consuming one quota.

```java
@Test
void keysCannotCrossTenantModeOrSchema() {
    assertThat(service.cacheKey(100L, "9", AiParseMode.BATCH, "abc"))
        .isEqualTo("ai:parse:cache:100:9:BATCH:v2:abc");
    assertThat(service.cacheKey(101L, "9", AiParseMode.SINGLE, "abc"))
        .isNotEqualTo(service.cacheKey(100L, "9", AiParseMode.BATCH, "abc"));
}
```

- [ ] **Step 2: Run and confirm RED**

Run: `mvn -Dtest=AiParsePolicyServiceTest test`

Expected: FAIL because policy classes are absent.

- [ ] **Step 3: Move policy out of the controller**

Implement this fixed order: block TTL check, cache read, cache-miss rate increment, callback, success/418 cache. The store owns Redis commands; the service owns key construction and thresholds. Its entry point is:

```java
public <T> T execute(long tenantId, String userId, AiParseMode mode, String text,
                     Class<T> resultType, ThrowingSupplier<String> modelCall)
```

Return cached JSON through `ObjectMapper`; cached `__418__` calls `recordRejection()` before throwing. Rate keys are `ai:parse:rate:{tenant}:{user}:{mode}:v2`; rejection and block keys also contain tenant, user, mode, and version. Preserve single mode's 300-character truncation before hashing.

- [ ] **Step 4: Verify and commit**

Run: `mvn -Dtest=AiParsePolicyServiceTest test`

Expected: PASS for ordering, isolation, cached 418, fifth-block, and quota assertions.

```bash
git add src/main/java/top/hetao/shiyuanticketmp/ai src/test/java/top/hetao/shiyuanticketmp/ai/policy
git commit -m "refactor: share tenant aware ai parse policy"
```

### Task 7: Add one-call batch AI parsing and strict schema validation

**Files:**
- Create: `src/main/java/top/hetao/shiyuanticketmp/ai/model/AiModelClient.java`
- Create: `src/main/java/top/hetao/shiyuanticketmp/ai/model/DashscopeAiModelClient.java`
- Create: `src/main/java/top/hetao/shiyuanticketmp/ai/batch/BatchAiParseRequest.java`
- Create: `src/main/java/top/hetao/shiyuanticketmp/ai/batch/BatchAiParseResult.java`
- Create: `src/main/java/top/hetao/shiyuanticketmp/ai/batch/BatchAiParseService.java`
- Modify: `src/main/java/top/hetao/shiyuanticketmp/ai/AiParseService.java`
- Modify: `src/main/java/top/hetao/shiyuanticketmp/ai/AiParseController.java`
- Test: `src/test/java/top/hetao/shiyuanticketmp/ai/batch/BatchAiParseServiceTest.java`
- Test: `src/test/java/top/hetao/shiyuanticketmp/ai/AiParseControllerBatchTest.java`

- [ ] **Step 1: Write failing tests** for 2,001 characters, 0/21 items, bad JSON, duplicate tracking numbers, unknown type, priority 0/4, tracking number absent from `sourceLine`, mixed types, 418, and 429 `Retry-After`. Verify `AiModelClient.complete()` exactly once for success and invalid JSON.

```java
@Test
void malformedBatchResponseIsNotRetried() {
    when(client.complete(anyString(), anyString())).thenReturn("not-json");
    assertThatThrownBy(() -> service.parse(TEXT, "CHANGE_ADDRESS"));
    verify(client, times(1)).complete(anyString(), eq(TEXT));
}
```

- [ ] **Step 2: Run and confirm RED**

Run: `mvn -Dtest=BatchAiParseServiceTest,AiParseControllerBatchTest test`

Expected: FAIL because `/api/ai/parse-batch` and batch service are absent.

- [ ] **Step 3: Implement one model call and validation**

Move the existing HTTP request into `DashscopeAiModelClient.complete(systemPrompt, userText)`. Let the existing single service retain its current retry loop; batch must call the client once with a prompt requiring raw item `type`, `sourceLine`, `trackingNo`, title, description, address, priority, and warnings.

```java
public BatchAiParseResult parse(String text, String expectedType) {
    if (text == null || text.isBlank() || text.length() > 2000) throw bad("text 长度必须为 1-2000");
    String json = client.complete(BATCH_SYSTEM_PROMPT, text); // exactly one call
    RawBatch raw = readAndValidate(json, text);
    return toPublicResult(raw, expectedType);
}
```

Validate `sourceLine` against the original nonblank line and require its tracking number to occur case-insensitively in that line. If raw item types differ, return top-level `type` as `expectedType` when valid or null otherwise, and attach `TYPE_CONFLICT:<type>` warnings to conflicting rows so the editor preserves them for review. A 418 uses the shared policy marker; no partial schema result is cached.

Add `POST /api/ai/parse-batch`; acquire tenant from `TenantContext` and user from Sa-Token. Attach `Retry-After` on policy block/rate exceptions.

- [ ] **Step 4: Verify and commit**

Run: `mvn -Dtest=BatchAiParseServiceTest,AiParseControllerBatchTest,AiParsePolicyServiceTest test`

Expected: PASS, with one client call per uncached batch and preserved single behavior.

```bash
git add src/main/java/top/hetao/shiyuanticketmp/ai src/test/java/top/hetao/shiyuanticketmp/ai
git commit -m "feat: parse work order batches with one ai call"
```

### Task 8: Add frontend batch state and API calls

**Files:**
- Modify: `../front/src/api/workorder.js`
- Create: `../front/src/utils/workorder-create-state.js`
- Create: `../front/tests/workorder-create-state.test.mjs`

- [ ] **Step 1: Write failing Node tests** for 1/20/manual lines, empty/21 lines, case-insensitive duplicates, mixed AI warnings preserving rows, one stable key across timeout retry, and a new key only after an editable payload changes.

```javascript
test('timeout retry reuses the locked request key', () => {
  const state = createSubmissionState(() => 'fixed-key')
  const locked = state.lock({ type: 'INTERCEPT', items: [item('YT1')] })
  state.fail({ code: 'ECONNABORTED' })
  assert.equal(state.lock(locked.payload).key, 'fixed-key')
  assert.equal(state.editable, false)
})
```

- [ ] **Step 2: Run and confirm RED**

Run from `../front`: `node --test tests/workorder-create-state.test.mjs`

Expected: FAIL with module-not-found.

- [ ] **Step 3: Implement pure state and API adapters**

```javascript
export function aiParseBatch(text, expectedType) {
  return request({ url: '/ai/parse-batch', method: 'post', data: { text, expectedType } })
}

export function createWorkOrderBatch(data, idempotencyKey) {
  return request({ url: '/workorders/batch', method: 'post', data,
    headers: { 'Idempotency-Key': idempotencyKey } })
}
```

Generate keys with `crypto.randomUUID().replaceAll('-', '')`. Manual extraction ignores blank lines and uses `line-{sourceLine}` client IDs. Lock a deep-cloned payload at first create attempt; only an HTTP business response unlocks it, while Axios timeout/network uncertainty keeps the payload and key locked for safe replay.

- [ ] **Step 4: Verify and commit**

Run from `../front`: `node --test tests/workorder-create-state.test.mjs`

Expected: PASS for limit, duplicate, draft, and idempotency state cases.

```bash
git add src/api/workorder.js src/utils/workorder-create-state.js tests/workorder-create-state.test.mjs
git commit -m "feat: add batch work order client state"
```

### Task 9: Make common attachment uploads independently retryable

**Files:**
- Create: `../front/src/utils/upload-task-queue.js`
- Create: `../front/tests/upload-task-queue.test.mjs`
- Modify: `../front/src/components/FileUpload.vue`

- [ ] **Step 1: Write the failing queue test**

```javascript
test('retry runs only failed work-order/file pairs', async () => {
  const calls = []
  const queue = createUploadTaskQueue(async task => {
    calls.push(task.key)
    if (task.key === '92:local-a' && calls.length === 2) throw new Error('PUT failed')
    return `${task.key}:file-id`
  }, 2)
  await queue.run(['91', '92'], [{ localId: 'local-a', file: new Blob(['x']) }])
  await queue.retryFailed()
  assert.deepEqual(calls, ['91:local-a', '92:local-a', '92:local-a'])
})
```

- [ ] **Step 2: Run and confirm RED**

Run from `../front`: `node --test tests/upload-task-queue.test.mjs`

Expected: FAIL with module-not-found.

- [ ] **Step 3: Refactor `FileUpload.vue` around stable local IDs**

Assign each selected file `localId: crypto.randomUUID()` and key every task as ``${String(workOrderId)}:${localId}``. For each pair perform the existing `presign -> PUT -> confirm` sequence, thereby creating a distinct backend file record per work order. Remove succeeded tasks from the runnable set immediately; retain failed tasks and their original `File`.

Expose:

```javascript
defineExpose({
  uploadAll: bizId => uploadForTargets([String(bizId)]),
  uploadForTargets,
  retryFailed,
  reset,
  getTaskResults: () => taskQueue.results(),
})
```

`reset()` and `onBeforeUnmount()` must call `URL.revokeObjectURL()` for every local preview and clear files/tasks. Render per-task failure counts and a “仅重试失败附件” action. Do not call a create API from this component.

- [ ] **Step 4: Verify and commit**

Run from `../front`: `node --test tests/upload-task-queue.test.mjs`

Expected: PASS for bounded concurrency, distinct pair keys, and retry-only-failed behavior.

```bash
git add src/components/FileUpload.vue src/utils/upload-task-queue.js tests/upload-task-queue.test.mjs
git commit -m "fix: retry common attachments per work order"
```

### Task 10: Build one single/batch creation editor and replace both copies

**Files:**
- Create: `../front/src/components/workorder/WorkOrderCreateEditor.vue`
- Modify: `../front/src/views/workorder/create.vue`
- Modify: `../front/src/views/workorder/list.vue`
- Create: `../front/e2e-batch-workorder.cjs`

- [ ] **Step 1: Add failing Playwright coverage**

Intercept the three APIs and assert: both existing entry points render `[data-testid="workorder-create-editor"]`; 400/418/429 leaves rows editable; mixed rows show warnings; timeout retry sends the same key and payload; a batch business failure shows no partial success; after one attachment fails, retry sends only that pair; closing/resetting removes preview images.

Run from `../front` with both apps started: `node e2e-batch-workorder.cjs`

Expected: FAIL because the shared editor/test IDs and batch controls are absent.

- [ ] **Step 2: Implement the shared state machine**

The component owns `editing -> parsing -> reviewing -> validating -> creating -> uploading -> completed|attachment_partial_failure -> retrying_uploads`. Its public surface is:

```javascript
const props = defineProps({ embedded: { type: Boolean, default: false } })
const emit = defineEmits(['completed', 'cancel'])
defineExpose({ reset })

const mode = ref('single')
const batchType = ref('')
const rows = ref([])
const createdItems = ref([])
```

Both station-side modes remove the current external-identity controls and never construct `tenantId`, `submitterId`, `senderStaffId`, or `conversationId`; the compatible single backend endpoint itself is unchanged for integration callers. Batch mode submits one top-level type, requires every row to pass the backend-equivalent limits, and shows an editable table before creation. On success, freeze the batch, pass all returned string IDs to `FileUpload.uploadForTargets()`, and never call create again during attachment retry. Warn that original browser `File` objects cannot be recovered after navigation.

- [ ] **Step 3: Replace both duplicated implementations**

`create.vue` becomes a page shell around the editor and routes to the list only after completion. The `list.vue` dialog embeds the same component, reloads the table on `completed`, and calls `editor.reset()` on a safe close. If created orders still have failed attachments, require confirmation before dialog close; the message must direct the user to retry now or reselect files from each work-order detail.

- [ ] **Step 4: Run E2E/build and commit**

Run from `../front`: `node e2e-batch-workorder.cjs`

Expected: PASS for both entry points, draft preservation, stable idempotency retry, atomic-result UI, and attachment retry.

Run from `../front`: `npm run build`

Expected: Vite exits 0 and writes `dist/`.

```bash
git add src/components/workorder/WorkOrderCreateEditor.vue src/views/workorder/create.vue src/views/workorder/list.vue e2e-batch-workorder.cjs
git commit -m "feat: unify single and batch work order editor"
```

### Task 11: Final contract verification and Apifox synchronization

**Files:**
- Modify in Apifox project `8260787`: `POST /api/ai/parse-batch`
- Modify in Apifox project `8260787`: `POST /api/workorders/batch`
- Verify only: all files above

- [ ] **Step 1: Run focused and compile verification**

Run from `backend/`:

```bash
mvn -Dtest=WorkOrderBatchRequestSqlTest,BatchWorkOrderValidatorTest,WorkOrderCreationServiceTest,BatchWorkOrderServiceTest,BatchWorkOrderTransactionTest,WorkOrderBatchControllerTest,AiParsePolicyServiceTest,BatchAiParseServiceTest,AiParseControllerBatchTest test
mvn compile
```

Expected: every focused test passes; compile ends with `BUILD SUCCESS`.

- [ ] **Step 2: Run frontend verification**

Run from `front/`:

```bash
node --test tests/workorder-create-state.test.mjs tests/upload-task-queue.test.mjs
npm run build
```

Expected: Node reports all tests passed; Vite exits 0.

- [ ] **Step 3: Synchronize exact API contracts**

Use the Apifox MCP for project `8260787` to upsert both endpoints, including the 2,000/20 limits, one-call/cache semantics, `Idempotency-Key` regex, forbidden identity fields, ordered string IDs, 400/409/429 responses, and `Retry-After`. Read both endpoints back and confirm no local `API.md` was created.

- [ ] **Step 4: Inspect the final diff and commit documentation-only corrections if needed**

Run separately in each repository: `git diff --check` and `git status --short`.

Expected: no whitespace errors; only intended source/tests/docs are present; no `target/`, `dist/`, secrets, screenshots, or environment files are staged.

If verification required a source correction, commit it in its owning repository with a narrow message; otherwise do not create an empty commit.
