-- V16: 为工单表添加 assignee_role 字段，支持按角色派发工单
-- 角色派发时 assignee_role 存储角色编码（如 WAREHOUSE_ADMIN），assignee_id 为 NULL
-- 用户派发时 assignee_id 存储用户 ID，assignee_role 为 NULL

ALTER TABLE `work_order`
    ADD COLUMN `assignee_role` VARCHAR(64) NULL DEFAULT NULL COMMENT '按角色派发时的角色编码' AFTER `assignee_id`;

CREATE INDEX `idx_work_order_assignee_role` ON `work_order` (`assignee_role`);
