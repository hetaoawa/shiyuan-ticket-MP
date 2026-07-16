-- V3__alter_for_mybatis_plus.sql
-- 为 MyBatis-Plus 框架改造做 DDL 调整

-- ----------------------------------------------------------------
-- 工单主表：添加 tenant_id、version、deleted、updated_at
-- ----------------------------------------------------------------

ALTER TABLE work_order
    ADD COLUMN tenant_id  BIGINT   NOT NULL DEFAULT 0 COMMENT '租户 ID' AFTER id,
    ADD COLUMN version    INT      NOT NULL DEFAULT 1 COMMENT '乐观锁版本号' AFTER tenant_id,
    ADD COLUMN deleted    TINYINT  NOT NULL DEFAULT 0 COMMENT '逻辑删除 0=正常 1=已删除' AFTER closed_at,
    ADD COLUMN updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间' AFTER deleted;

ALTER TABLE work_order
    ADD INDEX idx_tenant (tenant_id);

-- ----------------------------------------------------------------
-- WebHook 死信记录表：添加 tenant_id、version、deleted、updated_at
-- ----------------------------------------------------------------

ALTER TABLE webhook_dead_letter
    ADD COLUMN tenant_id  BIGINT   NOT NULL DEFAULT 0 COMMENT '租户 ID' AFTER id,
    ADD COLUMN version    INT      NOT NULL DEFAULT 1 COMMENT '乐观锁版本号' AFTER tenant_id,
    ADD COLUMN deleted    TINYINT  NOT NULL DEFAULT 0 COMMENT '逻辑删除 0=正常 1=已删除' AFTER status,
    ADD COLUMN updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间' AFTER deleted;

ALTER TABLE webhook_dead_letter
    ADD INDEX idx_tenant (tenant_id);
