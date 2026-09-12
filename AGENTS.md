# AGENTS.md - OpenCode 开发代理指南

## 代理分工
- 主代理只负责拆分任务、审查子代理改动、指出问题并派发修复；不要直接承担大段代码修改。
- 代码修改、新功能、重构、迁移脚本等编辑工作交给 `general` 子代理。
- 查询文件、搜索引用、梳理架构等只读探索工作交给 `explore` 子代理。
- `general` 和 `explore` 子代理可使用当前环境中可用的模型，不限制具体模型。

## 项目事实
- 单体 Spring Boot 3.0.2 / Java 17 / MyBatis-Plus 3.5.5 / Sa-Token 1.38.0 项目，主类是 `top.hetao.shiyuanticketmp.ShiyuanTicketMpApplication`。
- 主包 `src/main/java/top/hetao/shiyuanticketmp/` 下的业务模块包括 `auth`、`workorder`、`webhook`、`file`、`audit`、`menu`、`express`、`ai`；`demos` 是脚手架示例，非核心业务。
- Controller 没有统一成功包装类，沿用手写 `Map<String,Object>` 返回 `code`、`message`、`data`；异常由 `GlobalExceptionHandler` 统一处理。
- `JacksonConfig` 会把所有 `Long/long` 序列化为字符串，避免前端 JS 精度丢失。

## 常用命令
- 编译验证：`mvn compile`
- 全量测试：`mvn test`
- 单测类：`mvn -Dtest=ShiyuanTicketMpApplicationTests test`
- 打包：`mvn package -DskipTests`
- 当前测试只有一个 `@SpringBootTest contextLoads`，运行测试需要可用的 dev 配置依赖。

## 配置与环境
- `application.properties` 只激活 `dev`；真实配置在 `application-dev.yml`，服务端口 `9860`。
- `application-dev.yml` 会导入 `optional:file:.env.local[.properties]`，`.env.local` 已 gitignore；但文件内仍有硬编码 MySQL、Redis、webhook secret，改动时不要误以为所有敏感配置都已外置。
- `opencode.json` 只配置了默认禁用的 MySQL、Redis MCP；需要连接时依赖 `MYSQL_*`、`REDIS_URL` 环境变量。

## 多租户与实体
- 业务实体默认继承 `BaseEntity`，字段为 `id`、`tenantId`、`createdAt`、`updatedAt`、`deleted`；没有 `version` 字段，也未配置乐观锁拦截器。
- `MybatisPlusConfig` 拦截器顺序是租户拦截器再分页拦截器；`TenantContext.isAdmin()` 会跳过所有租户过滤，未设置租户时默认 `0L`。
- 当前租户排除表：`sys_config`、`sys_dict`、`sys_permission`、`sys_user_role`、`sys_role_permission`、`sys_audit_log`、`work_order_comment`、`express_trace`。
- 新增无 `tenant_id` 的全局表或关联表时必须加入排除表；新增有 `tenant_id` 的表时不要随手排除。
- `SysPermission`、`SysUserRole`、`SysRolePermission` 不继承 `BaseEntity`；它们是全局/关联实体。

## 认证与权限
- Sa-Token 拦截器注册在 `/**`，白名单是 `/api/auth/**`、`/api/webhook`、`/api/webhook/**`、`/error`、`/favicon.ico`。
- 注意当前 `SaInterceptor` lambda 只有注释、没有显式 `StpUtil.checkLogin()`；改认证前先核实现状，别按注释假设已校验。
- 登录会在 Sa-Token session 写入 `tenantId` 和 `isAdmin`；`TenantInterceptor` 从 session 写入 `TenantContext`，请求结束清理。
- 权限控制优先使用 `@SaCheckPermission`；项目里仍有部分 `StpUtil.getLoginIdAsLong()` 用法，新增代码优先用更稳妥的字符串登录 ID 转换策略。

## 认证与用户安全约定
- `SysUser.password` 已用 `@JsonProperty(access = WRITE_ONLY)` 标注，用户列表/详情接口不会返回密码哈希；新增序列化场景时不要绕过此注解。
- `SysUser.externalUserId` 用于关联外部系统用户（如货主侧 senderStaffId）；全局唯一索引 `uk_sys_user_external_user_id` 保证不重复，MySQL 下 NULL 不触发唯一冲突；空白值由应用层统一转 NULL 存储。
- 创建/更新用户时，`UserService` 会在保存前校验 `externalUserId` 是否已被其他用户绑定（忽略租户），冲突则抛出业务异常；更新自身绑定允许。
- 可选字段（phone、email、externalUserId）清除语义：前端发送空字符串 `""` 时后端 `normalizeBlank()` 转为 NULL 存储；前端不传该字段（JSON 中缺失）时跳过更新保持原值。`createUser()` 和 `updateUser()` 均对 phone/email 应用 `normalizeBlank()`。
- 管理员重置密码接口（`PUT /api/users/{id}/reset-password`）请求体字段为 `{ newPassword }`，不含旧密码。
- 个人改密接口为 `PUT /api/auth/password`，请求体 `{ oldPassword, newPassword }`，需要登录，后端校验旧密码后 BCrypt 更新。
- 个人资料接口为 `PUT /api/auth/profile`，请求体 `{ nickname, phone, email }`，需要登录。

## 工单事件、缓存、异步
- 工单状态变化发布 `WorkOrderStateChangedEvent`，监听器负责 webhook、审计、物流关闭、缓存刷新等副作用。
- Webhook 等耗时任务使用 `@Async("webhookExecutor")`，线程池配置在 `AsyncConfig`。
- 工单缓存前缀 `workorder:`，TTL 10 分钟；状态变更监听器会先驱逐再 write-through。
- 工单列表、导出和审计日志时间范围参数统一使用 `createdStartTime` / `createdEndTime`，格式为 `yyyy-MM-dd HH:mm:ss`。
- 创建/重新提交工单时，前端显式传入 `type` 才覆盖类型；未传时创建自动解析、重新提交保留原类型。

## 数据库与接口文档
- SQL 迁移脚本在 `src/main/resources/db/migration/`，项目未集成 Flyway，脚本需要手动执行。
- 迁移号已有重复 `V10`：`V10__add_resubmit_force_reject_permissions.sql` 和 `V10__add_cargo_owner_fields.sql`；新增脚本先检查现有最大版本，目前已到 `V15`。
- 新增权限需要同步插入 `sys_permission` 及相应角色关联数据。
- 接口文档维护在 Apifox 项目 `8260787`；新增或修改接口后用 Apifox MCP 同步，不要新增 `API.md`。
## 已知易错点
- S3 预签名使用 AWS SDK v2 lambda 风格 API，例如 `s3Presigner.presignPutObject(r -> ...)`，不要套用 v1 写法。
- `sys_audit_log`、`work_order_comment` 实体/表包含租户字段但当前被租户拦截器排除；改租户逻辑前要确认业务意图。
- `application-dev.yml` 中 webhook、S3、express、AI 配置依赖环境变量；本地启动缺变量会失败。
- **可选字段清除必须用 `LambdaUpdateWrapper.set(field, null)`**：MyBatis-Plus 的 `updateById()` 默认跳过 null 字段（字段策略 NOT_NULL），直接 `setXxx(null)` 后 `updateById()` 不会生成 `SET col = NULL`。对于需要显式清除的字段（如 `phone`、`email`、`externalUserId`），必须使用 `LambdaUpdateWrapper` 的 `.set()` 方法绕过此限制。不要全局改为 `field-strategy: always`，以免误更新其他字段。

## 角色派发与可见性
- 工单支持两种派发方式：按用户（`assigneeId`）和按角色（`assigneeRole`），二者互斥。
- 按角色派发时仅允许 `WAREHOUSE_ADMIN` 角色编码；`CARGO_OWNER` 和 `SYSTEM_ADMIN` 不可被派发。
- 可见性规则：SYSTEM_ADMIN 看全部；CARGO_OWNER 只看自己提交的；WAREHOUSE_ADMIN 看分配给自己或分配给自己角色的工单；多角色取并集。
- 重新提交（resubmit）同时清除 `assigneeId` 和 `assigneeRole`。
- 驳回（reject）和强制驳回（forceReject）均清除 `assigneeId`、`assigneeRole`、`assignedAt`，使云仓侧无法再看到该工单详情。

## 租户用户管理
- `CreateUserRequest` 包含可选字段 `tenantId`；SYSTEM_ADMIN 创建用户时可指定租户，不传则使用当前租户上下文。
- `UserService.updateUserFromMap` 支持通过 Map 更新 `tenantId`，前端传入时生效。
- 租户选项接口 `GET /api/admin/users/tenants` 从 `sys_user` 表提取不重复的 `tenant_id`，始终包含系统默认租户 0。
- 工单列表/详情接口返回 `assigneeName`（处理人昵称）、`assigneeRoleName`（角色名称）、`submitterName`（提交人昵称）瞬态字段，Controller 层批量填充。

## Webhook 与消息推送
- 钉钉和货主 webhook 调度器均支持工单详情链接；配置项分别为 `webhook.dingtalk.work-order-detail-base-url` 和 `webhook.cargo-owner.work-order-detail-base-url`。
- 详情链接路径格式为 `{base-url}/workorder/detail/{workOrderId}`，与前端路由一致。
- 货主侧消息为纯文本格式，处理链接以 `处理链接：<url>` 形式追加在消息末尾；未配置 base URL 时不发送链接。
- `AbstractWebhookDispatcher` 的 `timeoutSeconds`、`maxRetry`、`baseDelayMs` 通过 `@Value` 从配置注入；`webhook.timeout-seconds` 默认 10 秒，当前配置为 20 秒。
- 钉钉和货主侧调度器均继承基类超时配置，无需单独设置。
- `AssignPermissionsRequest` 同时接受 `permission_ids`（snake_case）和 `permissionIds`（camelCase），通过 `@JsonAlias` 实现。
- `RoleService.assignPermissions()` 校验 permissionIds 列表非 null，防止意外清空权限；传空列表 `[]` 表示有意清空。

## 权限与菜单
- 权限列表 API：`GET /api/admin/roles/permissions/all`，需要 `role:view` 权限，返回全部 `SysPermission` 记录。
- BUTTON 类型菜单节点挂在对应 MENU 节点下，`permission_code` 存储权限编码，`visible=0` 不显示在侧边栏。
- 系统管理页面菜单（用户/角色/菜单管理）已设置 `permission_code`（user:view / role:view / menu:view），动态菜单按权限过滤。

## 外部用户 ID 映射与工单创建校验
- `external_user_id` 列在 `sys_user` 表上，`VARCHAR(64) NULL`，全局唯一索引 `uk_sys_user_external_user_id (external_user_id)`（V14 迁移）。
- 空白字符串由 `UserService.normalizeBlank()` 统一转 NULL 存储，避免唯一索引冲突和语义歧义。
- Webhook 入站（`/api/webhook/cargo-owner`）为无认证端点；通过 `SysUserMapper.selectByExternalUserIdIgnoreTenant` 忽略租户查找系统用户；`senderStaffId` 必填且必须映射到已存在的系统用户，否则返回 400 拒绝创建。
- 认证接口 `POST /api/workorders` 的 `CreateWorkOrderRequest` 支持可选字段 `senderStaffId` 和 `conversationId`；当 `senderStaffId` 非空非空白时同样要求映射到系统用户，映射成功后 `submitterId` 取映射用户 ID；未传 `senderStaffId` 时沿用当前登录用户。`conversationId` 也仅在非空非空白时写入。
- `WorkOrderServiceImpl.create()` 有防御性 `submitterId` 非空校验，任何创建路径缺失提交人均会抛出业务异常。
- **Webhook 创建工单时显式继承映射系统用户的租户**：`CargoOwnerWebhookController` 在创建工单前调用 `order.setTenantId(submitter.getTenantId())` 和 `TenantContext.setTenantId(...)`，避免因无 Sa-Token session 导致租户拦截器默认注入 `tenant_id = 0`。认证接口 `POST /api/workorders` 传入 `senderStaffId` 时同样显式设置 `order.setTenantId(externalUser.getTenantId())`，确保 SYSTEM_ADMIN 代创建时工单落到正确租户。
- `GET /api/auth/me` 响应包含 `externalUserId` 字段。

## 用户创建与角色绑定
- `CreateUserRequest` 包含可选字段 `roleIds`，同时接受 `role_ids`（snake_case，通过 `@JsonAlias`）。
- `UserService.createUser()` 在保存用户后自动调用 `assignRoles()` 绑定角色；前端无需额外调用分配角色接口。
- 编辑用户时仍需单独调用 `POST /api/admin/users/{id}/roles` 更新角色（始终调用，空列表表示清除所有角色）。

## AI 解析缓存与 418 限流
- `AiParseController` 实现了文本哈希缓存和 418 拒绝限流机制。
- **缓存**：对截断后的文本计算 SHA-256 哈希，Redis key `ai:parse:cache:{userId}:{hash}`，TTL 10 分钟。缓存成功结果和 418 拒绝标记（`__418__`）。
- **418 拒绝计数**：使用 Redis ZSET `ai:parse:rej:{userId}` 记录拒绝时间戳，60 秒滑动窗口。缓存命中 418 也计入拒绝次数。
- **封禁**：单用户 60 秒内累计 5 次 418 拒绝后，设置 `ai:parse:blocked:{userId}` 封禁 10 分钟。封禁期间返回 HTTP 429 并附带剩余秒数。
- **全局限速**：保留原有每用户每分钟 10 次请求限制（`ai:parse:rate:{userId}`）。
- 418 拒绝响应保持原有格式：HTTP 400 + `{code: 400, message: "不合法的输入"}`。
