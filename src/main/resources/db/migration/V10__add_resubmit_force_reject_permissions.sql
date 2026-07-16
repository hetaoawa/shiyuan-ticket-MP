-- V10: 新增工单重新提交和强制驳回权限

-- 新增权限
INSERT INTO sys_permission (id, permission_code, permission_name) VALUES
(115, 'workorder:resubmit', '工单重新提交'),
(116, 'workorder:force-reject', '工单强制驳回');

-- SYSTEM_ADMIN 绑定新权限
INSERT INTO sys_role_permission (sys_role_permission.id,role_id, permission_id) VALUES
(115,1, 115),
(116,1, 116);

-- WAREHOUSE_ADMIN 绑定重新提交权限（仓库管理员可帮货主重新提交）
INSERT INTO sys_role_permission (sys_role_permission.id,role_id, permission_id) VALUES
(117,2, 115);
