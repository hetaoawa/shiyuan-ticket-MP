-- V5: 工单操作审计日志表
CREATE TABLE IF NOT EXISTS sys_audit_log (
    id              BIGINT          NOT NULL COMMENT '雪花ID',
    tenant_id       BIGINT          NOT NULL DEFAULT 0 COMMENT '租户ID',
    biz_type        VARCHAR(50)     NOT NULL COMMENT '业务类型（如 WORK_ORDER）',
    biz_id          BIGINT          NOT NULL COMMENT '业务ID（如工单ID）',
    action          VARCHAR(50)     NOT NULL COMMENT '操作动作（CREATE/ASSIGN/CLOSE/REJECT）',
    operator_id     BIGINT          NOT NULL COMMENT '操作人ID',
    detail          TEXT            DEFAULT NULL COMMENT '操作详情（JSON）',
    version         INT             NOT NULL DEFAULT 1 COMMENT '乐观锁',
    created_at      DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    updated_at      DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    deleted         TINYINT         NOT NULL DEFAULT 0 COMMENT '逻辑删除',
    PRIMARY KEY (id),
    INDEX idx_tenant_biz (tenant_id, biz_type, biz_id),
    INDEX idx_operator (operator_id),
    INDEX idx_created (created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='操作审计日志表';
