-- ============================================================================
-- init_schema.sql
-- Standalone initialization script: DROP + CREATE all tables + seed system data
-- Generated from live DB schema and migration history
-- MySQL syntax, dependency-safe order
-- ============================================================================

SET FOREIGN_KEY_CHECKS = 0;

-- ============================================================================
-- 1. DROP TABLES (reverse dependency order)
-- ============================================================================

DROP TABLE IF EXISTS `sys_user_role`;
DROP TABLE IF EXISTS `sys_role_permission`;
DROP TABLE IF EXISTS `sys_audit_log`;
DROP TABLE IF EXISTS `work_order_comment`;
DROP TABLE IF EXISTS `work_order`;
DROP TABLE IF EXISTS `express_trace`;
DROP TABLE IF EXISTS `webhook_dead_letter`;
DROP TABLE IF EXISTS `sys_file`;
DROP TABLE IF EXISTS `sys_menu`;
DROP TABLE IF EXISTS `sys_user`;
DROP TABLE IF EXISTS `sys_role`;
DROP TABLE IF EXISTS `sys_permission`;

SET FOREIGN_KEY_CHECKS = 1;

-- ============================================================================
-- 2. CREATE TABLES
-- ============================================================================

-- --------------------------------------------------------------------------
-- sys_permission (global, no tenant_id)
-- --------------------------------------------------------------------------
CREATE TABLE `sys_permission` (
    `id` BIGINT NOT NULL COMMENT '权限ID（雪花ID）',
    `permission_code` VARCHAR(128) NOT NULL COMMENT '权限编码',
    `permission_name` VARCHAR(128) NOT NULL COMMENT '权限名称',
    `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    PRIMARY KEY (`id`),
    UNIQUE INDEX `uk_permission_code` (`permission_code`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='权限表';

-- --------------------------------------------------------------------------
-- sys_role
-- --------------------------------------------------------------------------
CREATE TABLE `sys_role` (
    `id` BIGINT NOT NULL COMMENT '角色ID（雪花ID）',
    `tenant_id` BIGINT NOT NULL DEFAULT 0 COMMENT '租户 ID',
    `role_code` VARCHAR(64) NOT NULL COMMENT '角色编码',
    `role_name` VARCHAR(64) NOT NULL COMMENT '角色名称',
    `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    `deleted` TINYINT NOT NULL DEFAULT 0 COMMENT '逻辑删除',
    PRIMARY KEY (`id`),
    UNIQUE INDEX `uk_role_code` (`tenant_id`, `role_code`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='角色表';

-- --------------------------------------------------------------------------
-- sys_user
-- --------------------------------------------------------------------------
CREATE TABLE `sys_user` (
    `id` BIGINT NOT NULL COMMENT '用户ID（雪花ID）',
    `tenant_id` BIGINT NOT NULL DEFAULT 0 COMMENT '租户 ID',
    `version` INT NOT NULL DEFAULT 1 COMMENT '乐观锁版本号',
    `username` VARCHAR(64) NOT NULL COMMENT '用户名',
    `password` VARCHAR(128) NOT NULL COMMENT '密码（BCrypt加密）',
    `nickname` VARCHAR(64) DEFAULT NULL COMMENT '昵称',
    `phone` VARCHAR(20) DEFAULT NULL COMMENT '手机号',
    `email` VARCHAR(128) DEFAULT NULL COMMENT '邮箱',
    `status` TINYINT NOT NULL DEFAULT 1 COMMENT '状态 1=启用 0=禁用',
    `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    `deleted` TINYINT NOT NULL DEFAULT 0 COMMENT '逻辑删除',
    PRIMARY KEY (`id`),
    UNIQUE INDEX `uk_username` (`tenant_id`, `username`),
    INDEX `idx_phone` (`phone`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='用户表';

-- --------------------------------------------------------------------------
-- sys_menu
-- --------------------------------------------------------------------------
CREATE TABLE `sys_menu` (
    `id` BIGINT NOT NULL COMMENT '雪花ID',
    `tenant_id` BIGINT NOT NULL DEFAULT 0 COMMENT '租户ID',
    `parent_id` BIGINT DEFAULT 0 COMMENT '父菜单ID（0=顶级）',
    `menu_name` VARCHAR(100) NOT NULL COMMENT '菜单名称',
    `menu_code` VARCHAR(100) NOT NULL COMMENT '菜单编码（唯一标识）',
    `path` VARCHAR(200) DEFAULT NULL COMMENT '路由路径',
    `icon` VARCHAR(100) DEFAULT NULL COMMENT '图标',
    `sort_order` INT NOT NULL DEFAULT 0 COMMENT '排序号',
    `menu_type` VARCHAR(20) NOT NULL DEFAULT 'MENU' COMMENT '菜单类型（DIR/MENU/BUTTON）',
    `permission_code` VARCHAR(100) DEFAULT NULL COMMENT '关联权限编码',
    `visible` TINYINT NOT NULL DEFAULT 1 COMMENT '是否可见',
    `version` INT NOT NULL DEFAULT 1 COMMENT '乐观锁',
    `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    `deleted` TINYINT NOT NULL DEFAULT 0 COMMENT '逻辑删除',
    PRIMARY KEY (`id`),
    UNIQUE INDEX `uk_code` (`menu_code`, `tenant_id`),
    INDEX `idx_parent` (`parent_id`),
    INDEX `idx_tenant` (`tenant_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='动态菜单表';

-- --------------------------------------------------------------------------
-- sys_file
-- --------------------------------------------------------------------------
CREATE TABLE `sys_file` (
    `id` BIGINT NOT NULL COMMENT '雪花ID',
    `tenant_id` BIGINT NOT NULL DEFAULT 0 COMMENT '租户ID',
    `original_name` VARCHAR(500) NOT NULL COMMENT '原始文件名',
    `storage_key` VARCHAR(500) NOT NULL COMMENT 'S3 存储 Key（含目录路径）',
    `file_size` BIGINT NOT NULL COMMENT '文件大小（字节）',
    `content_type` VARCHAR(100) DEFAULT NULL COMMENT 'MIME 类型',
    `biz_type` VARCHAR(50) DEFAULT NULL COMMENT '业务类型（如 WORK_ORDER_IMAGE）',
    `biz_id` BIGINT DEFAULT NULL COMMENT '关联业务ID（如工单ID）',
    `uploader_id` BIGINT NOT NULL COMMENT '上传人ID',
    `download_url` VARCHAR(1000) DEFAULT NULL COMMENT '下载地址（可选，缓存用）',
    `version` INT NOT NULL DEFAULT 1 COMMENT '乐观锁',
    `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    `deleted` TINYINT NOT NULL DEFAULT 0 COMMENT '逻辑删除',
    PRIMARY KEY (`id`),
    INDEX `idx_tenant` (`tenant_id`),
    INDEX `idx_biz` (`biz_type`, `biz_id`),
    INDEX `idx_uploader` (`uploader_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='文件元数据表';

-- --------------------------------------------------------------------------
-- webhook_dead_letter
-- --------------------------------------------------------------------------
CREATE TABLE `webhook_dead_letter` (
    `id` BIGINT NOT NULL COMMENT '主键（雪花ID）',
    `tenant_id` BIGINT NOT NULL DEFAULT 0 COMMENT '租户 ID',
    `version` INT NOT NULL DEFAULT 1 COMMENT '乐观锁版本号',
    `event_id` VARCHAR(64) NOT NULL COMMENT '原始事件 ID，与 Header X-Event-Id 一致',
    `event_type` VARCHAR(64) NOT NULL COMMENT '事件类型，如 WORK_ORDER.CLOSED',
    `target_url` VARCHAR(512) NOT NULL COMMENT '投递目标 URL',
    `payload` MEDIUMTEXT NOT NULL COMMENT '原始请求体 JSON，补偿时直接重投',
    `last_error` VARCHAR(1024) DEFAULT NULL COMMENT '最后一次失败错误描述',
    `attempts` INT NOT NULL DEFAULT 0 COMMENT '累计尝试次数（含首次+重试）',
    `status` VARCHAR(16) NOT NULL DEFAULT 'PENDING' COMMENT '状态: PENDING/RESOLVED/IGNORED',
    `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '死信记录创建时间',
    `deleted` TINYINT NOT NULL DEFAULT 0 COMMENT '逻辑删除 0=正常 1=已删除',
    `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    `resolved_at` DATETIME DEFAULT NULL COMMENT '管理员处理时间',
    `resolved_by` VARCHAR(64) DEFAULT NULL COMMENT '处理管理员用户名',
    PRIMARY KEY (`id`),
    INDEX `idx_status_created` (`status`, `created_at`),
    INDEX `idx_event_id` (`event_id`),
    INDEX `idx_tenant` (`tenant_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='WebHook 死信记录表';

-- --------------------------------------------------------------------------
-- express_trace
-- --------------------------------------------------------------------------
CREATE TABLE `express_trace` (
    `id` BIGINT NOT NULL,
    `tenant_id` BIGINT NOT NULL DEFAULT 0,
    `tracking_no` VARCHAR(64) NOT NULL COMMENT '物流单号',
    `cp_code` VARCHAR(32) DEFAULT NULL COMMENT '快递公司编码',
    `logistics_status` VARCHAR(32) DEFAULT NULL COMMENT '物流状态码（ACCEPT/TRANSPORT/DELIVERING/DELIVERED）',
    `logistics_status_desc` VARCHAR(64) DEFAULT NULL COMMENT '物流状态描述',
    `response_json` MEDIUMTEXT NOT NULL COMMENT '完整物流响应 JSON',
    `fetched_at` DATETIME NOT NULL COMMENT '获取时间',
    `expires_at` DATETIME DEFAULT NULL COMMENT '过期时间（已签收为 NULL，表示永不过期）',
    `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    `deleted` TINYINT NOT NULL DEFAULT 0,
    PRIMARY KEY (`id`),
    UNIQUE INDEX `uk_tracking_no` (`tracking_no`),
    INDEX `idx_expires_at` (`expires_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='物流轨迹落库表';

-- --------------------------------------------------------------------------
-- work_order
-- --------------------------------------------------------------------------
CREATE TABLE `work_order` (
    `id` BIGINT NOT NULL COMMENT '主键（雪花ID）',
    `tenant_id` BIGINT NOT NULL DEFAULT 0 COMMENT '租户 ID',
    `version` INT NOT NULL DEFAULT 1 COMMENT '乐观锁版本号',
    `title` VARCHAR(200) NOT NULL COMMENT '工单标题',
    `description` TEXT DEFAULT NULL COMMENT '工单详情',
    `tracking_no` VARCHAR(50) DEFAULT NULL COMMENT '物流单号',
    `target_address` VARCHAR(500) DEFAULT NULL COMMENT '目标地址',
    `type` VARCHAR(20) DEFAULT 'OTHER' COMMENT '工单类型',
    `priority` TINYINT NOT NULL DEFAULT 2 COMMENT '优先级 1=低 2=中 3=高',
    `status` VARCHAR(20) NOT NULL COMMENT '状态: PENDING/IN_PROGRESS/CLOSED/REJECTED',
    `submitter_id` BIGINT NOT NULL COMMENT '提交人 ID',
    `assignee_id` BIGINT DEFAULT NULL COMMENT '处理人 ID（派发后赋值）',
    `resolution` TEXT DEFAULT NULL COMMENT '处理结论（关单时填写）',
    `rejection_reason` TEXT DEFAULT NULL COMMENT '驳回原因',
    `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `assigned_at` DATETIME DEFAULT NULL COMMENT '派发时间',
    `closed_at` DATETIME DEFAULT NULL COMMENT '关闭/驳回时间',
    `conversation_id` VARCHAR(128) DEFAULT NULL COMMENT '货主侧群ID',
    `sender_staff_id` VARCHAR(64) DEFAULT NULL COMMENT '货主侧发送人ID',
    `deleted` TINYINT NOT NULL DEFAULT 0 COMMENT '逻辑删除 0=正常 1=已删除',
    `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (`id`),
    INDEX `idx_status` (`status`),
    INDEX `idx_assignee` (`assignee_id`),
    INDEX `idx_submitter` (`submitter_id`),
    INDEX `idx_tenant` (`tenant_id`),
    INDEX `idx_tracking_no` (`tracking_no`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='工单主表';

-- --------------------------------------------------------------------------
-- work_order_comment
-- --------------------------------------------------------------------------
CREATE TABLE `work_order_comment` (
    `id` BIGINT NOT NULL,
    `tenant_id` BIGINT NOT NULL DEFAULT 0,
    `work_order_id` BIGINT NOT NULL COMMENT '工单ID',
    `content` TEXT NOT NULL COMMENT '评论内容',
    `commenter_id` BIGINT NOT NULL COMMENT '评论人ID',
    `comment_type` VARCHAR(20) NOT NULL DEFAULT 'COMMENT' COMMENT 'COMMENT=评论 NOTE=备注',
    `attachments` TEXT DEFAULT NULL COMMENT '附件ID列表JSON',
    `version` INT NOT NULL DEFAULT 1,
    `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    `deleted` TINYINT NOT NULL DEFAULT 0,
    PRIMARY KEY (`id`),
    INDEX `idx_wo_id` (`work_order_id`),
    INDEX `idx_tenant` (`tenant_id`),
    INDEX `idx_commenter` (`commenter_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='工单评论/备注';

-- --------------------------------------------------------------------------
-- sys_audit_log
-- --------------------------------------------------------------------------
CREATE TABLE `sys_audit_log` (
    `id` BIGINT NOT NULL COMMENT '雪花ID',
    `tenant_id` BIGINT NOT NULL DEFAULT 0 COMMENT '租户ID',
    `biz_type` VARCHAR(50) NOT NULL COMMENT '业务类型（如 WORK_ORDER）',
    `biz_id` BIGINT NOT NULL COMMENT '业务ID（如工单ID）',
    `action` VARCHAR(50) NOT NULL COMMENT '操作动作（CREATE/ASSIGN/CLOSE/REJECT）',
    `operator_id` BIGINT DEFAULT NULL COMMENT '操作人ID（系统操作可为空）',
    `detail` TEXT DEFAULT NULL COMMENT '操作详情（JSON）',
    `version` INT NOT NULL DEFAULT 1 COMMENT '乐观锁',
    `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    `deleted` TINYINT NOT NULL DEFAULT 0 COMMENT '逻辑删除',
    PRIMARY KEY (`id`),
    INDEX `idx_tenant_biz` (`tenant_id`, `biz_type`, `biz_id`),
    INDEX `idx_operator` (`operator_id`),
    INDEX `idx_created` (`created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='操作审计日志表';

-- --------------------------------------------------------------------------
-- sys_role_permission (global, no tenant_id)
-- --------------------------------------------------------------------------
CREATE TABLE `sys_role_permission` (
    `id` BIGINT NOT NULL COMMENT '主键',
    `role_id` BIGINT NOT NULL COMMENT '角色ID',
    `permission_id` BIGINT NOT NULL COMMENT '权限ID',
    PRIMARY KEY (`id`),
    UNIQUE INDEX `uk_role_permission` (`role_id`, `permission_id`),
    INDEX `idx_permission_id` (`permission_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='角色权限关联表';

-- --------------------------------------------------------------------------
-- sys_user_role (global, no tenant_id)
-- --------------------------------------------------------------------------
CREATE TABLE `sys_user_role` (
    `id` BIGINT NOT NULL COMMENT '主键',
    `user_id` BIGINT NOT NULL COMMENT '用户ID',
    `role_id` BIGINT NOT NULL COMMENT '角色ID',
    PRIMARY KEY (`id`),
    UNIQUE INDEX `uk_user_role` (`user_id`, `role_id`),
    INDEX `idx_role_id` (`role_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='用户角色关联表';

-- ============================================================================
-- 3. SEED DATA
-- ============================================================================

-- --------------------------------------------------------------------------
-- sys_permission (24 rows)
-- --------------------------------------------------------------------------
INSERT INTO `sys_permission` (`id`, `permission_code`, `permission_name`, `created_at`) VALUES
(1, 'workorder:create', '创建工单', '2026-05-12 08:08:57'),
(2, 'workorder:assign', '派发工单', '2026-05-12 08:08:57'),
(3, 'workorder:close', '关闭工单', '2026-05-12 08:08:57'),
(4, 'workorder:reject', '驳回工单', '2026-05-12 08:08:57'),
(5, 'workorder:view', '查看工单', '2026-05-12 08:08:57'),
(6, 'deadletter:view', '查看死信', '2026-05-12 08:08:57'),
(7, 'deadletter:retry', '重试死信', '2026-05-12 08:08:57'),
(8, 'deadletter:ignore', '忽略死信', '2026-05-12 08:08:57'),
(101, 'user:view', '用户查看', '2026-05-13 05:56:15'),
(102, 'user:create', '用户创建', '2026-05-13 05:56:15'),
(103, 'user:update', '用户编辑', '2026-05-13 05:56:15'),
(104, 'user:delete', '用户删除', '2026-05-13 05:56:15'),
(105, 'role:view', '角色查看', '2026-05-13 05:56:15'),
(106, 'role:create', '角色创建', '2026-05-13 05:56:15'),
(107, 'role:update', '角色编辑', '2026-05-13 05:56:15'),
(108, 'role:delete', '角色删除', '2026-05-13 05:56:15'),
(109, 'menu:view', '菜单查看', '2026-05-13 05:56:15'),
(110, 'menu:create', '菜单创建', '2026-05-13 05:56:15'),
(111, 'menu:update', '菜单编辑', '2026-05-13 05:56:15'),
(112, 'menu:delete', '菜单删除', '2026-05-13 05:56:15'),
(113, 'workorder:export', '工单导出', '2026-05-15 03:55:53'),
(114, 'workorder:comment', '工单评论', '2026-05-15 03:55:53'),
(115, 'workorder:resubmit', '工单重新提交', '2026-05-15 20:14:13'),
(116, 'workorder:force-reject', '工单强制驳回', '2026-05-15 20:14:13');

-- --------------------------------------------------------------------------
-- sys_role (3 rows)
-- --------------------------------------------------------------------------
INSERT INTO `sys_role` (`id`, `tenant_id`, `role_code`, `role_name`, `created_at`, `updated_at`, `deleted`) VALUES
(1, 100, 'CARGO_OWNER', '货主', '2026-05-12 08:08:55', '2026-05-14 09:43:19', 0),
(2, 100, 'WAREHOUSE_ADMIN', '云仓管理员', '2026-05-12 08:08:55', '2026-05-14 09:43:19', 0),
(3, 0, 'SYSTEM_ADMIN', '系统管理员', '2026-05-12 08:08:55', '2026-05-12 08:08:55', 0);

-- --------------------------------------------------------------------------
-- sys_role_permission (31 valid rows; live DB also has duplicate orphan rows for permission_id 20250513000000000, excluded)
-- --------------------------------------------------------------------------
INSERT INTO `sys_role_permission` (`id`, `role_id`, `permission_id`) VALUES
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
(14, 3, 8),
(113, 3, 113),
(114, 3, 114),
(115, 1, 115),
(116, 1, 116),
(117, 2, 115),
(201, 3, 101),
(202, 3, 102),
(203, 3, 103),
(204, 3, 104),
(205, 3, 105),
(206, 3, 106),
(207, 3, 107),
(208, 3, 108),
(209, 3, 109),
(210, 3, 110),
(211, 3, 111),
(212, 3, 112);

-- --------------------------------------------------------------------------
-- sys_menu (9 non-deleted rows, test row id 2054535066206785500 excluded)
-- --------------------------------------------------------------------------
INSERT INTO `sys_menu` (`id`, `tenant_id`, `parent_id`, `menu_name`, `menu_code`, `path`, `icon`, `sort_order`, `menu_type`, `permission_code`, `visible`, `version`, `created_at`, `updated_at`, `deleted`) VALUES
(1001, 100, 0, '工单管理', 'workorder', '/workorder', 'file-text', 1, 'DIR', NULL, 1, 1, '2026-05-12 11:09:30', '2026-05-14 09:43:28', 0),
(1002, 100, 0, '系统管理', 'system', '/system', 'setting', 99, 'DIR', NULL, 1, 1, '2026-05-12 11:09:30', '2026-05-14 09:43:28', 0),
(2001, 100, 1001, '工单列表', 'workorder:list', '/workorder/list', 'list', 1, 'MENU', 'workorder:view', 1, 1, '2026-05-12 11:09:30', '2026-05-14 09:43:28', 0),
(2002, 100, 1001, '创建工单', 'workorder:create', '/workorder/create', 'plus', 2, 'MENU', 'workorder:create', 1, 1, '2026-05-12 11:09:30', '2026-05-14 09:43:28', 0),
(3001, 100, 1002, '用户管理', 'system:user', '/system/user', 'user', 1, 'MENU', NULL, 1, 1, '2026-05-12 11:09:30', '2026-05-14 09:43:28', 0),
(3002, 100, 1002, '角色管理', 'system:role', '/system/role', 'team', 2, 'MENU', NULL, 1, 1, '2026-05-12 11:09:30', '2026-05-15 03:02:44', 0),
(3003, 100, 1002, '菜单管理', 'system:menu', '/system/menu', 'menu', 3, 'MENU', NULL, 1, 1, '2026-05-12 11:09:30', '2026-05-14 09:43:28', 0),
(3004, 100, 1002, '死信管理', 'system:deadletter', '/system/deadletter', 'warning', 4, 'MENU', 'deadletter:view', 1, 1, '2026-05-12 11:09:30', '2026-05-14 09:43:28', 0),
(3005, 100, 1002, '审计日志', 'system:audit', '/system/audit', 'file-search', 5, 'MENU', NULL, 1, 1, '2026-05-12 11:09:30', '2026-05-14 09:43:28', 0);

-- --------------------------------------------------------------------------
-- sys_user (3 non-deleted users)
-- --------------------------------------------------------------------------
INSERT INTO `sys_user` (`id`, `tenant_id`, `version`, `username`, `password`, `nickname`, `phone`, `email`, `status`, `created_at`, `updated_at`, `deleted`) VALUES
(1001, 0, 1, 'admin', '$2a$10$a4tVy.1v.rYoP71UgPk0IuPUG0lMpWuOSGyhe9zSdGTdCwRkfTrFW', '系统管理员', NULL, NULL, 1, '2026-05-12 08:21:18', '2026-05-13 10:49:52', 0),
(1002, 100, 1, 'warehouse01', '$2a$10$a4tVy.1v.rYoP71UgPk0IuPUG0lMpWuOSGyhe9zSdGTdCwRkfTrFW', '云仓操作员', NULL, NULL, 1, '2026-05-12 08:21:18', '2026-05-14 13:33:19', 0),
(1003, 100, 1, 'cargo01', '$2a$10$a4tVy.1v.rYoP71UgPk0IuPUG0lMpWuOSGyhe9zSdGTdCwRkfTrFW', '货主张三', NULL, NULL, 1, '2026-05-12 08:21:18', '2026-05-14 13:33:19', 0);

-- --------------------------------------------------------------------------
-- sys_user_role (3 rows)
-- --------------------------------------------------------------------------
INSERT INTO `sys_user_role` (`id`, `user_id`, `role_id`) VALUES
(1, 1001, 3),
(2, 1002, 2),
(3, 1003, 1);
