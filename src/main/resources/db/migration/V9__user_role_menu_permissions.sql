-- V9: 用户管理/角色管理/菜单管理 权限初始化
INSERT INTO sys_permission (id, permission_code, permission_name) VALUES
(101, 'user:view', '用户查看'),
(102, 'user:create', '用户创建'),
(103, 'user:update', '用户编辑'),
(104, 'user:delete', '用户删除'),
(105, 'role:view', '角色查看'),
(106, 'role:create', '角色创建'),
(107, 'role:update', '角色编辑'),
(108, 'role:delete', '角色删除'),
(109, 'menu:view', '菜单查看'),
(110, 'menu:create', '菜单创建'),
(111, 'menu:update', '菜单编辑'),
(112, 'menu:delete', '菜单删除');

INSERT INTO sys_role_permission (id, role_id, permission_id) VALUES
(201, 3, 101), (202, 3, 102), (203, 3, 103), (204, 3, 104),
(205, 3, 105), (206, 3, 106), (207, 3, 107), (208, 3, 108),
(209, 3, 109), (210, 3, 110), (211, 3, 111), (212, 3, 112);
