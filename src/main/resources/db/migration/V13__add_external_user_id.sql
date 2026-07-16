-- V13: sys_user 添加 external_user_id 字段，用于关联外部系统用户

ALTER TABLE sys_user ADD COLUMN external_user_id VARCHAR(64) NULL COMMENT '外部系统用户ID' AFTER email;
CREATE UNIQUE INDEX uk_sys_user_external_user_id ON sys_user (external_user_id, tenant_id);
