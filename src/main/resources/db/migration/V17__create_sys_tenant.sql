-- Authoritative tenant master data. This migration is safe both after V0-V16 on a new
-- database and after a production database has been manually baselined at version 16.

CREATE TABLE IF NOT EXISTS `sys_tenant` (
    `id` BIGINT NOT NULL COMMENT 'Tenant ID',
    `tenant_code` VARCHAR(64) NOT NULL COMMENT 'Stable tenant code',
    `tenant_name` VARCHAR(100) NOT NULL COMMENT 'Tenant display name',
    `status` TINYINT NOT NULL DEFAULT 1 COMMENT '1=enabled, 0=disabled',
    `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    `deleted` TINYINT NOT NULL DEFAULT 0 COMMENT 'Logical deletion flag',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_sys_tenant_code` (`tenant_code`),
    KEY `idx_sys_tenant_status` (`status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Authoritative tenant master data';

INSERT IGNORE INTO `sys_tenant`
    (`id`, `tenant_code`, `tenant_name`, `status`, `deleted`)
VALUES
    (0, 'platform', '平台租户', 1, 0);

-- Backfill every tenant currently referenced by a tenant-scoped table. INSERT IGNORE keeps
-- operator-maintained tenant names/codes unchanged when production already contains a row.
-- Before enabling Flyway on an existing database, reconcile any pre-existing row whose id/code
-- conflicts with this generated backfill; INSERT IGNORE intentionally does not overwrite it.
INSERT IGNORE INTO `sys_tenant`
    (`id`, `tenant_code`, `tenant_name`, `status`, `deleted`)
SELECT tenant_id,
       CONCAT('tenant-', tenant_id),
       CONCAT('租户 ', tenant_id),
       1,
       0
FROM (
    SELECT tenant_id FROM sys_user
    UNION SELECT tenant_id FROM sys_role
    UNION SELECT tenant_id FROM work_order
    UNION SELECT tenant_id FROM sys_file
    UNION SELECT tenant_id FROM sys_menu
    UNION SELECT tenant_id FROM webhook_dead_letter
    UNION SELECT tenant_id FROM sys_audit_log
    UNION SELECT tenant_id FROM work_order_comment
) referenced_tenants
WHERE tenant_id IS NOT NULL AND tenant_id <> 0;
