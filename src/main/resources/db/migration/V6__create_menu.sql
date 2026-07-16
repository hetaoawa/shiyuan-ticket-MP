-- V6: 动态菜单表
CREATE TABLE IF NOT EXISTS sys_menu (
    id              BIGINT          NOT NULL COMMENT '雪花ID',
    tenant_id       BIGINT          NOT NULL DEFAULT 0 COMMENT '租户ID',
    parent_id       BIGINT          DEFAULT 0 COMMENT '父菜单ID（0=顶级）',
    menu_name       VARCHAR(100)    NOT NULL COMMENT '菜单名称',
    menu_code       VARCHAR(100)    NOT NULL COMMENT '菜单编码（唯一标识）',
    path            VARCHAR(200)    DEFAULT NULL COMMENT '路由路径',
    icon            VARCHAR(100)    DEFAULT NULL COMMENT '图标',
    sort_order      INT             NOT NULL DEFAULT 0 COMMENT '排序号',
    menu_type       VARCHAR(20)     NOT NULL DEFAULT 'MENU' COMMENT '菜单类型（DIR/MENU/BUTTON）',
    permission_code VARCHAR(100)    DEFAULT NULL COMMENT '关联权限编码',
    visible         TINYINT         NOT NULL DEFAULT 1 COMMENT '是否可见',
    version         INT             NOT NULL DEFAULT 1 COMMENT '乐观锁',
    created_at      DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    updated_at      DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    deleted         TINYINT         NOT NULL DEFAULT 0 COMMENT '逻辑删除',
    PRIMARY KEY (id),
    INDEX idx_parent (parent_id),
    INDEX idx_tenant (tenant_id),
    UNIQUE INDEX uk_code (menu_code, tenant_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='动态菜单表';

-- 初始菜单数据
INSERT INTO sys_menu (id, tenant_id, parent_id, menu_name, menu_code, path, icon, sort_order, menu_type, permission_code) VALUES
-- 一级菜单
(1001, 0, 0, '工单管理', 'workorder', '/workorder', 'file-text', 1, 'DIR', NULL),
(1002, 0, 0, '系统管理', 'system', '/system', 'setting', 99, 'DIR', NULL),

-- 工单管理子菜单
(2001, 0, 1001, '工单列表', 'workorder:list', '/workorder/list', 'list', 1, 'MENU', 'workorder:view'),
(2002, 0, 1001, '创建工单', 'workorder:create', '/workorder/create', 'plus', 2, 'MENU', 'workorder:create'),

-- 系统管理子菜单
(3001, 0, 1002, '用户管理', 'system:user', '/system/user', 'user', 1, 'MENU', NULL),
(3002, 0, 1002, '角色管理', 'system:role', '/system/user', 'team', 2, 'MENU', NULL),
(3003, 0, 1002, '菜单管理', 'system:menu', '/system/menu', 'menu', 3, 'MENU', NULL),
(3004, 0, 1002, '死信管理', 'system:deadletter', '/system/deadletter', 'warning', 4, 'MENU', 'deadletter:view'),
(3005, 0, 1002, '审计日志', 'system:audit', '/system/audit', 'file-search', 5, 'MENU', NULL);
