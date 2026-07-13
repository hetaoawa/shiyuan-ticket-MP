# 租户登录与写入生命周期保护修复设计

**日期：** 2026-07-14  
**状态：** 已确认

## 背景与目标

测试发现两类问题：新增用户、重置密码、个人修改密码等租户内写操作返回内部错误；登录页要求手工输入租户 ID，外部工单链接在登录失效后也无法自动选择正确租户并返回原页面。

本设计在不改变现有登录请求 `tenantCode` 协议和租户生命周期并发语义的前提下，修复共享锁查询解析异常，提供公开且最小化的租户选项，并让外部深链、登录跳转和登录后回跳完整保留租户及页面上下文。

## 共享锁查询异常修复

根因不是 BCrypt 或密码字段，而是近期加入的 `TenantLifecycleGuard`。写操作会调用 `SysTenantMapper.selectEnabledByIdForShare` 或 `selectNotDeletedByIdForShare`，两条 SQL 均以 MySQL `LOCK IN SHARE MODE` 结尾。MyBatis-Plus 3.5.5 内置 JSqlParser 4.6 无法解析该语法，`TenantLineInnerInterceptor` 在 SQL 发送至 MySQL 前即抛出解析异常，最终表现为 500。

在这两个 Mapper 方法上使用 MyBatis-Plus `@InterceptorIgnore(tenantLine = "true", dataPermission = "false")`，只跳过租户行拦截器。MyBatis-Plus 3.5.5 中 `InterceptorIgnore.dataPermission` 的注解默认值会令数据权限也被忽略，因此必须显式写为 `"false"`；其余拦截器保持默认启用。继续保留 `LOCK IN SHARE MODE`、事务边界和当前谓词：交互式写入只允许启用且未删除的租户，已提交事件派生的审计或死信写入允许停用但未删除的租户。`sys_tenant` 是全局租户目录，SQL 已按传入 `tenantId` 明确查询，因此该局部跳过不会扩大业务数据访问范围。`selectByIdForUpdate` 及其他查询不在本次修改范围内。

## 公开租户选项接口

新增无需登录的 `GET /api/auth/tenant-options`。该路径分别精确加入认证与租户上下文白名单，不开放 `/api/auth/**`；不复用要求登录的 `/api/admin/tenants/options`。

成功响应沿用项目格式：

```json
{
  "code": 200,
  "message": "获取租户选项成功",
  "data": [
    { "tenantCode": "platform", "tenantName": "平台租户" }
  ]
}
```

接口只返回启用租户，包含平台租户；每项只暴露稳定且唯一的 `tenantCode` 和展示用 `tenantName`，不返回数据库 ID、状态、时间或其他管理信息。列表按平台租户固定第一、其余租户依次按 `tenantName`、`tenantCode` 升序排列，以保证结果确定。前端加载失败时显示错误和重试入口；无可用租户时显示空状态并禁止提交登录，不退回自由输入。

## 登录页与默认选择

租户文本框改为可搜索下拉框。默认选择优先级固定为：

1. 登录页 URL 的 `tenant` 租户编码；
2. 本地记住且仍存在于接口结果中的租户编码；
3. `platform`；
4. 第一个可用租户。

如果 URL 明确指定的租户不存在或已停用，页面应提示该租户不可用并要求用户重新选择，不得静默切换至其他租户。用户切换租户后使用 `router.replace()` 更新 `/login?tenant=<tenantCode>`，同时保留 `redirect`。登录请求仍提交现有 `tenantCode` 字段。

## 外部深链与安全回跳

工单推送链接统一为：

```text
/workorder/detail/{id}?tenantCode=<tenantCode>
```

深链使用 `tenantCode`，避免与现有表示数据库租户 ID 的 `tenant` 参数混淆。`TenantService.getTenantCode` 与 `getTenantName` 的查询语义对齐：`tenantId=0` 合法，启用或停用但未删除且编码非空的租户均返回稳定编码；`null`、负数、不存在、逻辑删除或编码为空时返回 `null`。Webhook listener 在状态变更和评论事件提交队列前要求非空编码，缺失时抛出 `IllegalStateException`，避免无租户身份的事件入队。

钉钉与货主 webhook 均根据工单所属租户附加正确编码。统一 URL 构造器基于 raw URI 保留基础地址已有编码、query 和 fragment，仅编码新增的 `tenantCode`，并在追加详情路径前规范化多个尾斜杠；基础 URL 为空时返回 `null`，消息不展示详情链接。钉钉详情基础 URL 为可选配置，但机器人 access token 与 secret 仍为必填。

前端建立统一的登录重定向工具，供路由守卫和 Axios 401 处理共同使用：从当前深链的 `tenantCode` 推导登录页 `tenant`，使用 `to.fullPath` 或 `router.currentRoute.value.fullPath` 保存完整路径、query 和 hash，然后跳转为：

```text
/login?tenant=<tenantCode>&redirect=<完整原地址>
```

登录成功后仅接受以 `/` 开头且不以 `//` 开头的站内 `redirect`，通过 `router.replace()` 返回；非法、缺失或形成登录页循环的值回退到默认已登录首页。使用 Vue Router 的 query 与 route object 处理编码，不手工拼接重定向参数。重复 401 跳转应保持幂等，避免覆盖首次保存的目标地址。

## 测试与验收

后端通过 `SysTenantMapperInterceptorIgnoreTest` 解析真实 MyBatis-Plus 忽略策略，验证两条共享锁方法只跳过租户行拦截器，并保留数据权限、动态表名、全表更新防护和非法 SQL 等其他拦截行为；共享锁 SQL 的数量另以全仓搜索门禁核对。公开接口的 service、controller 和精确白名单测试验证平台优先、启用/删除过滤、最小 DTO 与无需登录的路径契约。Webhook focused tests 覆盖租户编码解析、两类推送链接、raw 编码、已有 query、尾斜杠、未配置基础 URL，以及缺失编码时在入队前失败。

前端为租户默认选择和统一重定向工具增加可直接运行的测试，并更新现有 E2E 登录步骤。覆盖 URL 优先、本地记忆回退、未知租户、接口失败、空列表、切换租户更新地址栏，以及路由守卫和 401 两条路径对 query/hash 的完整保留。最终执行后端 focused tests 与 `mvn compile`、前端直接测试与 `npm run build`；服务可用时执行相关 E2E。

核心验收路径为：访问 `/workorder/detail/123?tenantCode=acme&foo=bar#timeline`，未登录时进入带 `tenant=acme` 和完整 `redirect` 的登录页，默认选择 `acme`，登录成功后准确返回原始地址。

## 非目标与文档同步

本次不引入租户路径前缀，不开放根据工单 ID 反查租户的公共接口，不修改数据库唯一约束，不改变登录请求协议，也不削弱共享锁或租户停用/删除保护。接口新增及 webhook 链接参数变更必须同步至 Apifox 项目 `8260787`，不创建本地 `API.md`，且文档与测试中不得包含环境配置秘密。
