-- V0: security foundation required by V8/V9/V10/V13 and by application startup.
-- This migration is intentionally non-destructive. Existing production databases use the
-- manual V16 baseline and therefore skip this and every other legacy migration through V16.

CREATE TABLE IF NOT EXISTS `sys_user` (
    `id` BIGINT NOT NULL COMMENT 'Snowflake ID',
    `tenant_id` BIGINT NOT NULL DEFAULT 0 COMMENT 'Tenant ID',
    `username` VARCHAR(64) NOT NULL COMMENT 'Username',
    `password` VARCHAR(128) NOT NULL COMMENT 'BCrypt password hash',
    `nickname` VARCHAR(64) NULL COMMENT 'Display name',
    `phone` VARCHAR(20) NULL COMMENT 'Phone number',
    `email` VARCHAR(128) NULL COMMENT 'Email address',
    `status` TINYINT NOT NULL DEFAULT 1 COMMENT '1=enabled, 0=disabled',
    `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT 'Created at',
    `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT 'Updated at',
    `deleted` TINYINT NOT NULL DEFAULT 0 COMMENT 'Logical deletion flag',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_sys_user_tenant_username` (`tenant_id`, `username`),
    KEY `idx_sys_user_phone` (`phone`),
    KEY `idx_sys_user_tenant` (`tenant_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='System users';

CREATE TABLE IF NOT EXISTS `sys_role` (
    `id` BIGINT NOT NULL COMMENT 'Snowflake ID',
    `tenant_id` BIGINT NOT NULL DEFAULT 0 COMMENT 'Tenant ID',
    `role_code` VARCHAR(64) NOT NULL COMMENT 'Role code',
    `role_name` VARCHAR(64) NOT NULL COMMENT 'Role name',
    `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT 'Created at',
    `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT 'Updated at',
    `deleted` TINYINT NOT NULL DEFAULT 0 COMMENT 'Logical deletion flag',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_sys_role_tenant_code` (`tenant_id`, `role_code`),
    KEY `idx_sys_role_tenant` (`tenant_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='System roles';

CREATE TABLE IF NOT EXISTS `sys_permission` (
    `id` BIGINT NOT NULL COMMENT 'Snowflake ID',
    `permission_code` VARCHAR(128) NOT NULL COMMENT 'Permission code',
    `permission_name` VARCHAR(128) NOT NULL COMMENT 'Permission name',
    `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT 'Created at',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_sys_permission_code` (`permission_code`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Global permissions';

CREATE TABLE IF NOT EXISTS `sys_user_role` (
    `id` BIGINT NOT NULL COMMENT 'Snowflake ID',
    `user_id` BIGINT NOT NULL COMMENT 'User ID',
    `role_id` BIGINT NOT NULL COMMENT 'Role ID',
    `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT 'Created at',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_sys_user_role` (`user_id`, `role_id`),
    KEY `idx_sys_user_role_role` (`role_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='User-role associations';

CREATE TABLE IF NOT EXISTS `sys_role_permission` (
    `id` BIGINT NOT NULL COMMENT 'Snowflake ID',
    `role_id` BIGINT NOT NULL COMMENT 'Role ID',
    `permission_id` BIGINT NOT NULL COMMENT 'Permission ID',
    `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT 'Created at',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_sys_role_permission` (`role_id`, `permission_id`),
    KEY `idx_sys_role_permission_permission` (`permission_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Role-permission associations';

-- IDs are stable because later legacy migrations V8/V9/V10/V15 reference roles 1, 2 and 3.
INSERT IGNORE INTO `sys_role` (`id`, `tenant_id`, `role_code`, `role_name`) VALUES
(1, 100, 'CARGO_OWNER', '货主'),
(2, 100, 'WAREHOUSE_ADMIN', '云仓管理员'),
(3, 0, 'SYSTEM_ADMIN', '系统管理员');

INSERT IGNORE INTO `sys_permission` (`id`, `permission_code`, `permission_name`) VALUES
(1, 'workorder:create', '创建工单'),
(2, 'workorder:assign', '派发工单'),
(3, 'workorder:close', '关闭工单'),
(4, 'workorder:reject', '驳回工单'),
(5, 'workorder:view', '查看工单'),
(6, 'deadletter:view', '查看死信'),
(7, 'deadletter:retry', '重试死信'),
(8, 'deadletter:ignore', '忽略死信');

INSERT IGNORE INTO `sys_role_permission` (`id`, `role_id`, `permission_id`) VALUES
(1, 1, 1),
(2, 1, 5),
(3, 1, 2),
(4, 2, 3),
(5, 2, 4),
(6, 2, 5),
(7, 3, 1),
(8, 3, 2),
(9, 3, 3),
(10, 3, 4),
(11, 3, 5),
(12, 3, 6),
(13, 3, 7),
(14, 3, 8);

-- One administrator is the minimum bootstrap account for a brand-new database. The hash and
-- account match the existing documented bootstrap data; operators must rotate it immediately.
INSERT IGNORE INTO `sys_user` (
    `id`, `tenant_id`, `username`, `password`, `nickname`, `status`
) VALUES (
    1001,
    0,
    'admin',
    '$2a$10$TxR8Wrmau4UYe39K6OERqeJCDo/HyZbxu4f7odnrbHEN7Ci./psau',
    '系统管理员',
    1
);

INSERT IGNORE INTO `sys_user_role` (`id`, `user_id`, `role_id`) VALUES
(1, 1001, 3);
