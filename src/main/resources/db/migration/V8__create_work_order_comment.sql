-- V8: 工单评论表 + 工单附件关联表

CREATE TABLE IF NOT EXISTS `work_order_comment` (
    `id` BIGINT NOT NULL,
    `tenant_id` BIGINT NOT NULL DEFAULT 0,
    `work_order_id` BIGINT NOT NULL COMMENT '工单ID',
    `content` TEXT NOT NULL COMMENT '评论内容',
    `commenter_id` BIGINT NOT NULL COMMENT '评论人ID',
    `comment_type` VARCHAR(20) NOT NULL DEFAULT 'COMMENT' COMMENT 'COMMENT=评论 NOTE=备注',
    `attachments` TEXT COMMENT '附件ID列表JSON，如 [1,2,3]',
    `version` INT NOT NULL DEFAULT 1,
    `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    `deleted` TINYINT NOT NULL DEFAULT 0,
    PRIMARY KEY (`id`),
    INDEX `idx_wo_id` (`work_order_id`),
    INDEX `idx_tenant` (`tenant_id`),
    INDEX `idx_commenter` (`commenter_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='工单评论/备注';

-- 添加评论相关权限
INSERT IGNORE INTO `sys_permission` (`id`, `permission_code`, `permission_name`, `created_at`) VALUES
(20250513000000001, 'workorder:comment', '工单评论', NOW()),
(20250513000000002, 'workorder:export', '工单导出', NOW());

-- 为 SYSTEM_ADMIN 分配评论和导出权限
INSERT IGNORE INTO `sys_role_permission` (`id`, `role_id`, `permission_id`) VALUES
(20250513000000001, 1, 20250513000000001),
(20250513000000002, 1, 20250513000000002);
