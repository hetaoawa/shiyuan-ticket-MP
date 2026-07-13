-- V15: 为系统管理菜单添加 permission_code，添加 BUTTON 类型菜单节点用于权限编辑
-- 确保 workorder:comment / workorder:export 权限行存在且分配给相应角色
-- 角色 ID 映射：1=CARGO_OWNER, 2=WAREHOUSE_ADMIN, 3=SYSTEM_ADMIN

-- 1. 为系统管理页面菜单添加 permission_code（使动态菜单按权限过滤）
UPDATE `sys_menu` SET `permission_code` = 'user:view'   WHERE `id` = 3001 AND `menu_code` = 'system:user';
UPDATE `sys_menu` SET `permission_code` = 'role:view'   WHERE `id` = 3002 AND `menu_code` = 'system:role';
UPDATE `sys_menu` SET `permission_code` = 'menu:view'   WHERE `id` = 3003 AND `menu_code` = 'system:menu';

-- 2. 确保 sys_permission 行存在（V8 已用相同大 ID 插入，INSERT IGNORE 保持幂等）
INSERT IGNORE INTO `sys_permission` (`id`, `permission_code`, `permission_name`, `created_at`) VALUES
(20250513000000001, 'workorder:comment', '工单评论', NOW()),
(20250513000000002, 'workorder:export',  '工单导出', NOW());

-- 3. 添加 BUTTON 类型菜单节点（挂在对应 MENU 下，用于角色权限编辑器展示）
-- 用户管理按钮
INSERT IGNORE INTO `sys_menu` (`id`, `tenant_id`, `parent_id`, `menu_name`, `menu_code`, `path`, `icon`, `sort_order`, `menu_type`, `permission_code`, `visible`) VALUES
(4001, 100, 3001, '新增用户',   'system:user:create',   NULL, NULL, 1, 'BUTTON', 'user:create',   0),
(4002, 100, 3001, '编辑用户',   'system:user:update',   NULL, NULL, 2, 'BUTTON', 'user:update',   0),
(4003, 100, 3001, '删除用户',   'system:user:delete',   NULL, NULL, 3, 'BUTTON', 'user:delete',   0);

-- 角色管理按钮
INSERT IGNORE INTO `sys_menu` (`id`, `tenant_id`, `parent_id`, `menu_name`, `menu_code`, `path`, `icon`, `sort_order`, `menu_type`, `permission_code`, `visible`) VALUES
(4004, 100, 3002, '新增角色',   'system:role:create',   NULL, NULL, 1, 'BUTTON', 'role:create',   0),
(4005, 100, 3002, '编辑角色',   'system:role:update',   NULL, NULL, 2, 'BUTTON', 'role:update',   0),
(4006, 100, 3002, '删除角色',   'system:role:delete',   NULL, NULL, 3, 'BUTTON', 'role:delete',   0);

-- 菜单管理按钮
INSERT IGNORE INTO `sys_menu` (`id`, `tenant_id`, `parent_id`, `menu_name`, `menu_code`, `path`, `icon`, `sort_order`, `menu_type`, `permission_code`, `visible`) VALUES
(4007, 100, 3003, '新增菜单',   'system:menu:create',   NULL, NULL, 1, 'BUTTON', 'menu:create',   0),
(4008, 100, 3003, '编辑菜单',   'system:menu:update',   NULL, NULL, 2, 'BUTTON', 'menu:update',   0),
(4009, 100, 3003, '删除菜单',   'system:menu:delete',   NULL, NULL, 3, 'BUTTON', 'menu:delete',   0);

-- 工单管理按钮
INSERT IGNORE INTO `sys_menu` (`id`, `tenant_id`, `parent_id`, `menu_name`, `menu_code`, `path`, `icon`, `sort_order`, `menu_type`, `permission_code`, `visible`) VALUES
(4010, 100, 1001, '工单评论',   'workorder:comment',    NULL, NULL, 3, 'BUTTON', 'workorder:comment', 0),
(4011, 100, 1001, '工单导出',   'workorder:export',     NULL, NULL, 4, 'BUTTON', 'workorder:export',  0);

-- 4. 确保 SYSTEM_ADMIN (role_id=3) 拥有 workorder:comment 和 workorder:export
-- V8 已为 CARGO_OWNER (role_id=1) 分配了这两个权限（sys_role_permission id 20250513000000001/002）
-- 使用 301+ 段 ID，避免与 V9 (201-212) 和 V10 (115-117) 冲突
INSERT IGNORE INTO `sys_role_permission` (`id`, `role_id`, `permission_id`) VALUES
(301, 3, 20250513000000001),
(302, 3, 20250513000000002);

-- 5. 确保 workorder:comment 分配给 CARGO_OWNER 和 WAREHOUSE_ADMIN
-- CARGO_OWNER (role_id=1) 在 V8 已分配，此处 INSERT IGNORE 幂等；新增 WAREHOUSE_ADMIN (role_id=2)
INSERT IGNORE INTO `sys_role_permission` (`id`, `role_id`, `permission_id`) VALUES
(303, 2, 20250513000000001);
