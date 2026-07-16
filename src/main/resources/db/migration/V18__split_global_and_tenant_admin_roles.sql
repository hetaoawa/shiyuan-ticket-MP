-- Split the platform administrator identity from tenant-local administrators.
-- Scope invariant: platform identity is tenant_id = 0; tenant administrators are tenant_id <> 0.
-- AUTO_INCREMENT is used only by migration INSERTs that omit id. Application inserts keep
-- supplying MyBatis-Plus Snowflake ids and remain compatible with AUTO_INCREMENT columns.
ALTER TABLE `sys_role`
    MODIFY COLUMN `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT 'Snowflake/application or migration-generated ID';
ALTER TABLE `sys_role_permission`
    MODIFY COLUMN `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT 'Snowflake/application or migration-generated ID';
ALTER TABLE `sys_user_role`
    MODIFY COLUMN `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT 'Snowflake/application or migration-generated ID';

-- Re-enable a pre-existing GLOBAL role if an interrupted/manual rollout created it first.
UPDATE `sys_role`
SET `role_name` = '全局系统管理员', `deleted` = 0
WHERE `tenant_id` = 0 AND `role_code` = 'GLOBAL_SYSTEM_ADMIN';

-- The normal legacy state has one tenant-0 SYSTEM_ADMIN and no GLOBAL role.
UPDATE `sys_role` legacy
LEFT JOIN `sys_role` existing
       ON existing.`tenant_id` = 0 AND existing.`role_code` = 'GLOBAL_SYSTEM_ADMIN'
SET legacy.`role_code` = 'GLOBAL_SYSTEM_ADMIN',
    legacy.`role_name` = '全局系统管理员',
    legacy.`deleted` = 0
WHERE legacy.`tenant_id` = 0
  AND legacy.`role_code` = 'SYSTEM_ADMIN'
  AND existing.`id` IS NULL;

-- Defensive/idempotent path: if both legacy and GLOBAL rows exist, merge every association.
INSERT IGNORE INTO `sys_user_role` (`user_id`, `role_id`)
SELECT ur.`user_id`, global_role.`id`
FROM `sys_user_role` ur
JOIN `sys_role` legacy ON legacy.`id` = ur.`role_id`
JOIN `sys_role` global_role
  ON global_role.`tenant_id` = 0 AND global_role.`role_code` = 'GLOBAL_SYSTEM_ADMIN'
WHERE legacy.`tenant_id` = 0 AND legacy.`role_code` = 'SYSTEM_ADMIN';

INSERT IGNORE INTO `sys_role_permission` (`role_id`, `permission_id`)
SELECT global_role.`id`, rp.`permission_id`
FROM `sys_role_permission` rp
JOIN `sys_role` legacy ON legacy.`id` = rp.`role_id`
JOIN `sys_role` global_role
  ON global_role.`tenant_id` = 0 AND global_role.`role_code` = 'GLOBAL_SYSTEM_ADMIN'
WHERE legacy.`tenant_id` = 0 AND legacy.`role_code` = 'SYSTEM_ADMIN';

DELETE ur FROM `sys_user_role` ur
JOIN `sys_role` legacy ON legacy.`id` = ur.`role_id`
WHERE legacy.`tenant_id` = 0 AND legacy.`role_code` = 'SYSTEM_ADMIN';
DELETE rp FROM `sys_role_permission` rp
JOIN `sys_role` legacy ON legacy.`id` = rp.`role_id`
WHERE legacy.`tenant_id` = 0 AND legacy.`role_code` = 'SYSTEM_ADMIN';
DELETE FROM `sys_role`
WHERE `tenant_id` = 0 AND `role_code` = 'SYSTEM_ADMIN';

-- Also supports installations where the legacy platform role was absent.
INSERT IGNORE INTO `sys_role` (`tenant_id`, `role_code`, `role_name`, `deleted`)
VALUES (0, 'GLOBAL_SYSTEM_ADMIN', '全局系统管理员', 0);

-- Every enabled business tenant has exactly one local SYSTEM_ADMIN role. The existing unique
-- key (tenant_id, role_code) makes this repeat-safe.
INSERT IGNORE INTO `sys_role` (`tenant_id`, `role_code`, `role_name`, `deleted`)
SELECT t.`id`, 'SYSTEM_ADMIN', '系统管理员', 0
FROM `sys_tenant` t
WHERE t.`id` <> 0 AND t.`status` = 1 AND t.`deleted` = 0;

UPDATE `sys_role` r
JOIN `sys_tenant` t ON t.`id` = r.`tenant_id`
SET r.`role_name` = '系统管理员', r.`deleted` = 0
WHERE r.`tenant_id` <> 0 AND r.`role_code` = 'SYSTEM_ADMIN'
  AND t.`status` = 1 AND t.`deleted` = 0;

-- Administrator permissions are resolved by permission_code through the shared permission
-- catalog; no production-specific permission ids are assumed.
INSERT IGNORE INTO `sys_role_permission` (`role_id`, `permission_id`)
SELECT r.`id`, p.`id`
FROM `sys_role` r
CROSS JOIN `sys_permission` p
WHERE r.`deleted` = 0
  AND ((r.`tenant_id` = 0 AND r.`role_code` = 'GLOBAL_SYSTEM_ADMIN')
    OR (r.`tenant_id` <> 0 AND r.`role_code` = 'SYSTEM_ADMIN'))
  AND p.`permission_code` IS NOT NULL;
