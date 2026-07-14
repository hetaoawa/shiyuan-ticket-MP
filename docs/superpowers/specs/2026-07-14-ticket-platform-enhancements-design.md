# 工单平台批量创建、租户集成与体验优化设计

## 1. 目标与范围

本设计覆盖后端 `backend/` 与前端 `front/`，在保持现有单工单接口、静态路由、Sa-Token 登录和 S3 两阶段上传兼容的前提下完成以下工作：

1. 站内用户可为同一工单类型批量输入并创建多个运单号，每个运单号对应一个独立工单；批量输入也支持一次性 AI 解析。
2. 外部业务集成配置改为租户隔离的数据库配置和网页管理，不再从 Spring 业务配置文件读取；旧生产配置一次性导入租户 `100`。
3. 平台共享域名的 SSL 证书由平台管理员管理，证书可不配置；Spring Boot 内嵌 Tomcat 直接提供 HTTPS，并支持网页上传和 acme.sh 自动续签导入，不引入 Nginx 等代理层。
4. 改善登录的密码记忆、Enter 焦点流转、租户登录失效重定向、雪花 ID 展示和动态菜单空目录行为。

数据库、Redis 和配置加密主密钥属于应用启动所需的平台基础设施，不属于“外部业务集成配置入库”的范围。QQ Bot 当前仓库没有消息消费者和发送协议，本期提供租户级配置、校验、脱敏 API 和运行时解析边界，不虚构机器人消息协议。

## 2. 总体架构与边界

改造分为六个边界清晰的模块：

- `workorder-batch`：批量草稿、校验、幂等记录、事务创建和结果返回。
- `ai-parse-policy`：单条与批量 AI 解析共用缓存、限流、418 拒绝计数和租户隔离策略。
- `tenant-integration`：租户级类型化集成配置、秘密加密、热更新和旧配置导入。
- `platform-ssl`：全局平台证书、内嵌 Tomcat HTTPS Connector、ACME challenge 和证书热重载。
- `auth-navigation`：登录记忆、焦点流、旧 session 兼容、租户重定向和菜单剪枝。
- `opaque-id-ui`：所有雪花 ID 的字符串展示、复制和表格防换行。

外部 AI 调用、对象存储上传和 WebSocket 连接不得放入批量建单数据库事务。租户和提交人只从认证会话与 `TenantContext` 获取，站内批量请求不接受 `tenantId`、`submitterId`、`senderStaffId` 或 `conversationId`。

## 3. 批量 AI 解析与批量建单

### 3.1 用户流程

创建编辑器保留“单个创建”默认模式，并增加“批量创建”模式。批量模式按以下状态流转：

```text
editing -> parsing(manual|AI) -> reviewing -> validating -> creating
        -> uploading -> completed
                       -> attachment_partial_failure -> retrying_uploads
```

用户粘贴一行一条的物流诉求，可先本地提取运单号，也可调用批量 AI。AI 结果必须进入可编辑校对表，不能直接创建。批次顶层只有一个 `type`；AI 识别出多种类型时，前端标记冲突，用户统一类型或删除冲突行后才可提交。

批量上限为 20 个有效工单，AI 原文上限为 2000 个字符。空行忽略；运单号 trim 后按大小写不敏感规则检查批内重复。`trackingNo` 和 `title` 必填，`trackingNo` 最大 50 字符，`title` 最大 200 字符，`targetAddress` 最大 500 字符，`priority` 仅允许 1 至 3；`CHANGE_ADDRESS` 类型要求目标地址非空。

### 3.2 批量 AI API

保留 `POST /api/ai/parse`，新增：

```http
POST /api/ai/parse-batch
Content-Type: application/json

{
  "text": "YT001 修改地址……\nYT002 修改地址……",
  "expectedType": "CHANGE_ADDRESS"
}
```

成功响应：

```json
{
  "code": 200,
  "message": "解析成功",
  "data": {
    "type": "CHANGE_ADDRESS",
    "items": [
      {
        "clientItemId": "line-1",
        "sourceLine": 1,
        "trackingNo": "YT001",
        "title": "修改地址工单 - YT001",
        "description": "……",
        "targetAddress": "……",
        "priority": 2,
        "warnings": []
      }
    ],
    "rejectedLines": []
  }
}
```

批量解析只调用模型一次。单条与批量端点共用 `AiParsePolicyService`，执行顺序保持为封禁检查、缓存读取、缓存未命中限流、模型调用、成功或 418 缓存。批量一次模型调用只消耗一次现有每分钟 10 次配额；418 只计一次，缓存命中 418 仍计入拒绝次数。缓存和限流键包含 `tenantId`、`userId`、解析模式和 schema 版本，防止管理员切换租户后复用其他租户的结果。批量文本超长直接返回 400，不静默截断；现有单条 300 字符兼容行为不改变。

模型响应必须经过结构校验、枚举校验、数量校验、重复校验和运单号原文归属校验。418 继续返回 HTTP 400 和现有“不合法的输入”响应；达到限制返回 HTTP 429，并提供 `Retry-After`。

### 3.3 批量创建 API 与幂等

新增：

```http
POST /api/workorders/batch
Idempotency-Key: 8-64字符的随机请求标识
Content-Type: application/json

{
  "type": "CHANGE_ADDRESS",
  "items": [
    {
      "clientItemId": "line-1",
      "trackingNo": "YT001",
      "title": "修改地址工单 - YT001",
      "description": "……",
      "targetAddress": "……",
      "priority": 2
    }
  ]
}
```

响应顺序与请求顺序一致，雪花 ID 始终为字符串：

```json
{
  "code": 200,
  "message": "批量创建成功",
  "data": {
    "batchId": "1980000000000000000",
    "createdCount": 1,
    "replayed": false,
    "items": [
      {
        "clientItemId": "line-1",
        "id": "1980000000000000001",
        "trackingNo": "YT001"
      }
    ]
  }
}
```

新增 `work_order_batch_request` 表，记录 `tenant_id`、请求用户、幂等键、规范化请求 SHA-256、工单 ID 列表和创建时间，并对 `(tenant_id, requester_id, idempotency_key)` 建唯一索引。同一个键与相同请求返回原结果且不重复发布事件；同一个键对应不同请求返回 HTTP 409。幂等记录和工单在同一事务提交，失败时全部回滚。

批量服务先完整校验所有项目，再锁定可写租户一次，固定当前登录用户为提交人，顺序插入每个工单并发布现有 CREATE 事件。任一校验或数据库错误使整批回滚；事件监听器仅在事务提交后运行。单建与批建共享内部准备和插入逻辑，不能通过同类自调用误以为获得独立事务。

### 3.4 附件

附件继续使用 `presign -> PUT -> confirm`，不改为 multipart。批量模式选择的一组公共附件会为每个已创建工单分别生成文件记录和对象上传，不能复用同一个 `fileId`。

前端以 `(workOrderId, localFileId)` 作为上传任务键，限制并发数，逐任务保存成功或失败。工单创建成功后锁定批次，附件失败只能重试失败任务，不能再次调用创建接口。页面离开后浏览器不能恢复原始 `File`；结果页必须明确提示用户在离开前重试，或进入具体工单详情重新选择附件。

`FileUpload.vue` 为本地文件生成稳定 `localId`，成功任务立即从待上传集合移除，并提供 reset、释放 object URL 和逐任务结果能力；单建也复用这一实现，修复现有部分成功后重复上传的问题。

## 4. 租户外部集成配置

### 4.1 数据模型

新增 `sys_tenant_integration`：

- `id`、`tenant_id`、`integration_type`、`enabled`；
- `config_json`：非敏感、可展示的结构化配置；
- `secret_ciphertext`、`secret_nonce`、`key_version`：AES-256-GCM 加密秘密；
- `config_version`：热更新版本；
- 创建和更新时间字段；
- 唯一约束 `(tenant_id, integration_type)`，配置通过更新或清空复用同一行，不通过逻辑删除制造重复版本。

类型包括 `DINGTALK`、`CARGO_OWNER`、`QQ_BOT_WS`、`S3`、`EXPRESS` 和 `AI`。Controller 使用类型化 DTO，不允许前端提交任意键值。GET 仅返回公开字段、配置完整状态和必要的掩码，不返回 token、secret、private key、access key 或密码。更新秘密遵循“字段缺失保持、显式 clear 清除、新值替换”语义。

AES-GCM 的 AAD 绑定 `tenantId + integrationType + fieldName`。平台加密主密钥和版本来自操作系统秘密或环境变量，不得与密文存入同一数据库。日志、异常、审计详情和响应均不得包含秘密明文。

### 4.2 运行时与热更新

现有 `@Value` 业务凭据改为按当前显式 tenantId 解析：

- 钉钉、货主、物流和 AI 每次操作从 resolver 取得当前版本配置；
- S3 客户端和 presigner 按 `(tenantId, configVersion)` 缓存，配置变化时关闭旧客户端并重建；
- QQ Bot WS 提供配置 resolver 和连接管理接口，但在缺少已定义的机器人消息协议时不建立虚构的发送消费者；
- 异步事件、重试和死信始终显式携带 tenantId，禁止依赖线程遗留上下文；
- 多实例通过 Redis 发布配置版本失效消息；本地短 TTL 缓存作为丢失消息的兜底。

新租户默认集成禁用，只有“配置完整且 enabled”才允许调用。不得继续使用“缺行默认 true”。配置变更需记录操作者、租户、类型、版本和动作，但不记录秘密。

前端系统设置页按类型展示独立表单，并根据 `settings:view`、`settings:update` 控制查看和编辑。切换租户后清除旧配置状态并重新加载。

### 4.3 旧版本迁移

数据库迁移从当前最大版本之后的 V24 开始。Flyway SQL 只创建表、索引、权限和必要的非秘密默认记录，不读取 YAML 或环境变量。

提供显式的一次性旧配置导入器：

- 目标业务租户固定为 `100`，执行前校验租户存在；
- 从旧 Spring properties 读取钉钉、货主、S3、物流和 AI 等值；
- 只填充数据库中缺失的字段，秘密加密后写入；
- 事务提交并记录不含明文的迁移审计和配置指纹；
- 成功后设置不可重复执行的迁移标记；
- 其他租户永不回退到旧全局配置；灰度期仅租户 100 可采用数据库优先的短期兼容读取，验证完成后删除回退。

旧 SSL 不是租户业务配置，迁移到平台全局证书记录，不写入租户 100。

## 5. 平台 SSL 与 acme.sh

### 5.1 全局模型与权限

SSL 仅服务平台共享域名，由 `GLOBAL_SYSTEM_ADMIN` 管理。新增无 `tenant_id` 的平台 SSL 配置和证书版本表，并加入租户拦截器排除表。配置包含启用状态、共享域名、HTTPS/ACME HTTP 端口、强制 HTTPS 状态、active certificate version、证书指纹、SAN、有效期、最近加载状态和错误摘要。

私钥与证书链解析为 PKCS12 后使用 AES-256-GCM 加密入库。上传时校验证书链、有效期、SAN 覆盖平台域名、私钥与公钥匹配，并支持 RSA 和 ECC。API 只返回主题、SAN、指纹、有效期和运行状态，永不回显私钥。

### 5.2 内嵌 Tomcat 运行模型

不配置启动期 `server.ssl.*`，保留现有 HTTP 端口作为 bootstrap/内网管理通道。`ApplicationReadyEvent` 后由 `EmbeddedTomcatSslManager` 读取平台证书：

- 没有证书或 SSL disabled：只运行 HTTP；
- 有有效 active certificate：向正在运行的 Tomcat Service 动态增加 HTTPS Connector；
- 上传或续签后：更新内存 KeyStore 并调用 Tomcat 10.1.5 的 `reloadSslHostConfig`；
- 热重载失败：保持旧 active certificate，记录失败状态；兼容兜底为停止、移除并重建 HTTPS Connector，HTTP 通道不中断；
- 禁用 SSL：安全移除 HTTPS Connector。

启用 SSL 后，公网 HTTP 除 ACME challenge 和本机 deploy API 外返回 308 到 HTTPS。bootstrap 端口应由防火墙限制为内网或受控管理来源，避免用户继续明文登录。绑定 80/443 需要操作系统授予低端口权限或使用宿主端口映射，端口必须空闲且防火墙放行。

### 5.3 acme.sh

应用提供严格限定 token 格式的 `/.well-known/acme-challenge/{token}` 响应，支持 acme.sh stateless HTTP-01。若公网 80 不可达，则运维使用 DNS-01；应用不执行外部 shell，也不读取 `~/.acme.sh` 内部文件。

平台管理员在网页生成一次性 deploy token，数据库只保存哈希、权限范围和失效时间。acme.sh 使用稳定的 `--install-cert` 输出路径，并在 `--reloadcmd` 中把 key/fullchain POST 到仅本机允许访问的 deploy API。后端验证 token、解析证书、原子写入候选版本、热重载成功后切换 active；相同指纹重复导入保持幂等。

首次启动无证书时 HTTPS 不可用，这是可选 SSL 的预期状态；平台管理员通过受控 HTTP bootstrap 上传首张证书。

## 6. 登录与租户重定向

“记住密码”使用浏览器标准密码管理器，不把密码写入 localStorage、sessionStorage 或可由前端脚本解密的自制密文。用户名输入使用 `autocomplete="username"`，密码使用 `autocomplete="current-password"`；现有复选框继续保存租户和账号，浏览器负责询问和恢复密码。若产品以后要求长期免登录，应另行实现可撤销的 HttpOnly remember-session，而不是保存密码。

焦点顺序为租户、账号、密码、提交：租户 Enter 选择后聚焦账号，账号 Enter 聚焦密码，密码 Enter 提交；恢复了租户和账号时初始化后直接聚焦密码。异步租户列表加载完成后再执行焦点决策。

401 重定向在清理状态前保存完整 `fullPath` 和有效租户 code，登录页选择优先级为显式 query 租户、重定向中租户、记住的租户、平台租户、首个可用租户。统一 query 字段名，避免 `tenant` 与 `tenantCode` 混用。

后端兼容旧 session 的 `tenantId`：普通用户存在旧键但缺少新 `activeTenantId` 时迁移到新键并校验租户；身份键损坏或租户已失效时主动注销旧 token 并返回明确 HTTP 401。全局管理员没有 active tenant 是合法待选择状态，使用稳定业务错误，不误判为过期登录。

## 7. 菜单与首屏路由

用户导航树在后端采用后序递归：先按权限处理子节点，再删除没有任何可导航后代的 DIR。MENU 按自身 permissionCode 判定；BUTTON 不进入导航树；管理端完整菜单树不受用户导航剪枝影响。`children` 始终返回数组。

前端递归标准化菜单作为防御：过滤 BUTTON、空 DIR、空路径、重复路径和无法匹配静态路由注册表的 MENU，不根据 menuCode 猜测不存在的路径。侧边栏组件支持递归层级。根路径跳转到当前用户第一个可访问静态路由，不再固定跳转工单列表。

## 8. 雪花 ID 展示

新增统一 `OpaqueId` 组件：始终执行 `String(value)`，完整值通过 tooltip 展示并可点击或键盘复制，空值显示统一占位。样式使用单行省略、等宽数字、`white-space: nowrap`、`overflow: hidden` 和 `text-overflow: ellipsis`。表格 ID 列采用统一最小宽度，不允许长 ID 自动换行撑高行。

工单、用户、租户、角色、审计、死信、事件和关联业务 ID 逐步替换为该组件。只有分页 total/page/size 可转 Number，业务 ID 和 ID 数组永不转 Number。

## 9. 错误语义与安全

- 400：输入为空、超长、超过批量上限、字段非法、批内重复、AI schema 不合法。
- 401：token 缺失、过期或旧 session 无法安全迁移。
- 403：缺少操作权限或非平台管理员访问 SSL。
- 409：幂等键用于不同请求、配置版本并发冲突。
- 429：AI 频率限制或 418 封禁，携带重试时间。
- 500：不可恢复的数据库或运行时错误；批量事务必须回滚。

前端 HTTP/业务错误提示仍由 `request.js` 唯一负责，视图层不重复 toast。秘密字段不进入日志、异常、审计详情或 API 响应。外部 URL 需限制协议和长度；QQ Bot WS 仅允许 `ws`/`wss`，生产配置建议强制 `wss`；Webhook 目标需防止 SSRF，至少禁止环回、链路本地和私网地址，平台明确允许的内网目标通过受控白名单配置。

## 10. 验证与验收

### 10.1 后端

- 批量 AI：缓存命中不消耗限流；缓存 418 计拒绝；第 5 次封禁；单/批/租户键隔离；一次批量只调用模型一次。
- AI schema：0/21 项、重复、未知类型、非法优先级、坏 JSON、原文不存在的运单号均拒绝。
- 批量创建：20 项成功产生 20 个独立字符串 ID；第 N 项失败整批零写入且无 AFTER_COMMIT 副作用。
- 幂等：相同键和请求返回相同 ID 且事件不重复；相同键不同请求返回 409；并发相同键只产生一批。
- 租户配置：跨租户不可读写；秘密不回显；GCM 篡改失败；保存后 resolver 与客户端缓存按版本更新。
- 迁移：只导入租户 100；其他租户无 fallback；重复运行不覆盖已配置值；审计无明文。
- SSL：无证书启动 HTTP 可用；启用后真实 TLS 握手成功；轮换后新连接指纹变化且 Spring Context 未重启；失败保留旧证书；禁用后移除 HTTPS；RSA/ECC、链、错配 key、过期、SAN 错误均覆盖。
- ACME：合法 challenge、非法 token、过期 deploy token、重复证书、非平台管理员访问和响应脱敏均覆盖。
- 登录/菜单：旧 session 迁移或 401、全局管理员待选租户、空系统设置目录剪枝、有权限目录保留、管理树不变。

### 10.2 前端

- 单建与批量模式共用一个创建编辑器，两个现有入口行为一致。
- AI 解析 1/20 条、重复、混合类型、缺字段、400/418/429 后均保留可校对草稿。
- 创建超时用同一幂等键重试不重复建单；整批业务失败无部分成功 UI。
- 第 N 个附件失败后只重试失败任务，成功任务不重复上传；重置和卸载释放预览 URL。
- 租户异步加载、记住账号、Enter 焦点、401 深链和租户恢复覆盖自动测试。
- 无权限系统设置不显示；仅有设置权限的用户从根路径进入第一个可访问页面。
- 所有已知 ID 表格保持单行，tooltip 和复制可用，业务 ID 不发生精度转换。
- 租户集成和平台 SSL 页面按权限、租户切换、秘密保持/清除、保存失败回滚正确工作。

### 10.3 构建与运行验证

- 后端从 `backend/` 运行 `mvn compile`，新增纯单元测试和不依赖外部 dev 服务的聚焦测试；可用依赖齐备时运行相关 Maven 测试。
- 前端从 `front/` 运行 `npm run build`，并扩展现有直接执行的 E2E/Node 测试，不新增不存在的 lint 或 typecheck 脚本。
- 所有新增和修改 API 同步到 Apifox 项目 `8260787`，不创建本地 `API.md`。

## 11. 上线顺序与回滚

1. 先部署 V24 起的表、权限和幂等结构，不启用新集成读取。
2. 部署双读迁移能力，仅租户 100 在限定灰度期允许旧值 fallback。
3. 执行并核对一次性导入，切换租户 100 到数据库配置，随后关闭旧业务配置读取。
4. 上线批量 AI、批量创建和前端编辑器；单建接口保持兼容。
5. 上线平台 SSL 页面和受控 HTTP bootstrap，上传候选证书并完成真实 TLS 握手后再启用强制 HTTPS。
6. 配置 acme.sh challenge 与 deploy token，完成一次测试续签和热重载。
7. 上线登录、菜单和 ID 展示修复。

集成配置可按类型关闭并回到“未配置/禁用”，但不得回退为所有租户共享旧凭据。SSL 热重载失败保留旧 active certificate；首次启用失败继续使用受控 HTTP。批量建单不改变现有单建接口，因此可通过隐藏批量入口回滚前端而不影响单建。
