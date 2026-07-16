-- V14: external_user_id 改为全局唯一（不再按租户区分）
-- Webhook 入站通过 external_user_id 查找用户时无租户上下文，
-- 若允许同 ID 跨租户会导致查找结果不确定，因此改为全局唯一。
-- MySQL InnoDB 下 UNIQUE 索引允许多个 NULL 行，空白已由应用层转 NULL，不受影响。

DROP INDEX uk_sys_user_external_user_id ON sys_user;
CREATE UNIQUE INDEX uk_sys_user_external_user_id ON sys_user (external_user_id);
