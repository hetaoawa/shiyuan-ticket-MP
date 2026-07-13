-- Complete tenant ownership, delivery metadata, and administrative permission boundaries.
-- V19 already added webhook_dead_letter.channel and its channel/status index; do not repeat it.

ALTER TABLE `webhook_dead_letter`
    ADD COLUMN `conversation_id` VARCHAR(128) NULL
        COMMENT 'Cargo-owner conversation/room captured for the original delivery' AFTER `target_url`,
    ADD COLUMN `sender_staff_id` VARCHAR(128) NULL
        COMMENT 'Cargo-owner recipient captured for the original delivery' AFTER `conversation_id`,
    ADD INDEX `idx_webhook_dead_letter_tenant_status_created`
        (`tenant_id`, `status`, `created_at`, `id`);

-- The cargo-owner body contains these values at the top level. Only JSON-valid and directly
-- determinable legacy values are backfilled; DingTalk and ambiguous records intentionally stay null.
UPDATE `webhook_dead_letter`
SET `conversation_id` = NULLIF(JSON_UNQUOTE(JSON_EXTRACT(
        IF(JSON_VALID(`payload`), `payload`, NULL), '$.room_id')), 'null'),
    `sender_staff_id` = NULLIF(JSON_UNQUOTE(JSON_EXTRACT(
        IF(JSON_VALID(`payload`), `payload`, NULL), '$.senderStaffId')), 'null')
WHERE `channel` = 'CARGO_OWNER'
  AND JSON_VALID(`payload`) = 1
  AND (`conversation_id` IS NULL OR `sender_staff_id` IS NULL);

-- Work-order ownership is authoritative for audit/comment rows. Rows that cannot be joined are
-- left platform-owned (tenant_id=0) and therefore remain unavailable inside business tenants.
UPDATE `sys_audit_log` audit_log
JOIN `work_order` work_order
  ON audit_log.`biz_type` = 'WORK_ORDER' AND work_order.`id` = audit_log.`biz_id`
SET audit_log.`tenant_id` = work_order.`tenant_id`
WHERE audit_log.`tenant_id` <> work_order.`tenant_id`;

UPDATE `work_order_comment` comment_record
JOIN `work_order` work_order ON work_order.`id` = comment_record.`work_order_id`
SET comment_record.`tenant_id` = work_order.`tenant_id`
WHERE comment_record.`tenant_id` <> work_order.`tenant_id`;

ALTER TABLE `sys_audit_log`
    ADD INDEX `idx_sys_audit_log_tenant_type_created`
        (`tenant_id`, `biz_type`, `created_at`, `id`);

ALTER TABLE `work_order_comment`
    ADD INDEX `idx_work_order_comment_tenant_order_created`
        (`tenant_id`, `work_order_id`, `deleted`, `created_at`, `id`);

INSERT IGNORE INTO `sys_permission` (`id`, `permission_code`, `permission_name`) VALUES
    (20260713000000011, 'audit:view', '查看租户审计日志'),
    (20260713000000012, 'webhook:monitor', '查看 WebHook 队列状态'),
    (20260713000000013, 'file:upload', '上传并确认业务文件'),
    (20260713000000014, 'file:view', '查看并下载业务文件'),
    (20260713000000015, 'file:delete', '删除业务文件');

-- A legacy migration and the old provisioning template granted force-reject to cargo owners.
-- Remove every such association before enforcing the administrator-only controller boundary.
DELETE role_permission
FROM `sys_role_permission` role_permission
JOIN `sys_role` role_record ON role_record.`id` = role_permission.`role_id`
JOIN `sys_permission` permission_record ON permission_record.`id` = role_permission.`permission_id`
WHERE role_record.`role_code` = 'CARGO_OWNER'
  AND permission_record.`permission_code` = 'workorder:force-reject';

INSERT IGNORE INTO `sys_role_permission` (`role_id`, `permission_id`)
SELECT role_record.`id`, permission_record.`id`
FROM `sys_role` role_record
JOIN `sys_permission` permission_record
  ON permission_record.`permission_code` = 'workorder:force-reject'
WHERE role_record.`deleted` = 0
  AND ((role_record.`tenant_id` > 0 AND role_record.`role_code` = 'SYSTEM_ADMIN')
    OR (role_record.`tenant_id` = 0 AND role_record.`role_code` = 'GLOBAL_SYSTEM_ADMIN'));

-- Existing tenant/platform administrators receive every new administrative permission.
INSERT IGNORE INTO `sys_role_permission` (`role_id`, `permission_id`)
SELECT role_record.`id`, permission_record.`id`
FROM `sys_role` role_record
JOIN `sys_permission` permission_record
  ON permission_record.`permission_code` IN
     ('audit:view', 'webhook:monitor', 'file:upload', 'file:view', 'file:delete')
WHERE role_record.`deleted` = 0
  AND ((role_record.`tenant_id` > 0 AND role_record.`role_code` = 'SYSTEM_ADMIN')
    OR (role_record.`tenant_id` = 0 AND role_record.`role_code` = 'GLOBAL_SYSTEM_ADMIN'));

-- Preserve existing work-order attachment capabilities for tenant business roles.
INSERT IGNORE INTO `sys_role_permission` (`role_id`, `permission_id`)
SELECT role_record.`id`, permission_record.`id`
FROM `sys_role` role_record
JOIN `sys_permission` permission_record
  ON permission_record.`permission_code` IN ('file:upload', 'file:view', 'file:delete')
WHERE role_record.`deleted` = 0
  AND role_record.`tenant_id` > 0
  AND role_record.`role_code` IN ('CARGO_OWNER', 'WAREHOUSE_ADMIN');
