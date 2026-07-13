-- Per-tenant external integration switches. Missing rows intentionally mean enabled so
-- existing tenants preserve their pre-migration behavior.

CREATE TABLE IF NOT EXISTS `sys_tenant_setting` (
    `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT 'Primary key',
    `tenant_id` BIGINT NOT NULL COMMENT 'Owning business tenant',
    `setting_key` VARCHAR(64) NOT NULL COMMENT 'Supported integration setting key',
    `setting_value` TINYINT(1) NOT NULL DEFAULT 1 COMMENT 'Boolean switch: 1=enabled, 0=disabled',
    `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    `deleted` TINYINT NOT NULL DEFAULT 0 COMMENT 'Logical deletion flag',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_sys_tenant_setting_tenant_key` (`tenant_id`, `setting_key`),
    KEY `idx_sys_tenant_setting_tenant` (`tenant_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Tenant external integration settings';

-- Channel is nullable for legacy rows whose original dispatcher cannot be inferred safely.
-- V2 creates this table on new databases; production baselined at V16 already has it.
ALTER TABLE `webhook_dead_letter`
    ADD COLUMN `channel` VARCHAR(32) NULL COMMENT 'Stable delivery channel code' AFTER `event_type`,
    ADD INDEX `idx_webhook_dead_letter_channel_status` (`channel`, `status`);

-- Backfill enabled business tenants. Disabled or newly-created tenants rely on the same
-- missing-row=true compatibility rule until an administrator saves explicit values.
INSERT IGNORE INTO `sys_tenant_setting`
    (`tenant_id`, `setting_key`, `setting_value`, `deleted`)
SELECT t.`id`, setting_keys.`setting_key`, 1, 0
FROM `sys_tenant` t
CROSS JOIN (
    SELECT 'externalInboundEnabled' AS `setting_key`
    UNION ALL SELECT 'externalCloseCallbackEnabled'
    UNION ALL SELECT 'dingTalkPushEnabled'
) setting_keys
WHERE t.`id` > 0 AND t.`status` = 1 AND t.`deleted` = 0;

-- Stable application permission IDs. Column lists omit created_at so this works with both
-- the V0 schema (which supplies a default) and canonical production permission tables.
INSERT IGNORE INTO `sys_permission` (`id`, `permission_code`, `permission_name`) VALUES
    (20260713000000001, 'settings:view', '查看租户外部集成设置'),
    (20260713000000002, 'settings:update', '更新租户外部集成设置');

-- V18 makes relation IDs AUTO_INCREMENT. Omitting id/created_at keeps this compatible with
-- both V0 relations (created_at has a default) and legacy production relations without it.
INSERT IGNORE INTO `sys_role_permission` (`role_id`, `permission_id`)
SELECT r.`id`, p.`id`
FROM `sys_role` r
JOIN `sys_permission` p
  ON p.`permission_code` IN ('settings:view', 'settings:update')
WHERE r.`deleted` = 0
  AND ((r.`tenant_id` > 0 AND r.`role_code` = 'SYSTEM_ADMIN')
    OR (r.`tenant_id` = 0 AND r.`role_code` = 'GLOBAL_SYSTEM_ADMIN'));
