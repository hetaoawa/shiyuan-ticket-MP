-- V1__create_work_order.sql
-- 工单主表

CREATE TABLE IF NOT EXISTS work_order
(
    id               BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
    title            VARCHAR(200) NOT NULL                COMMENT '工单标题',
    description      TEXT                                 COMMENT '工单详情',
    type             VARCHAR(20)           DEFAULT 'OTHER' COMMENT '工单类型',
    priority         TINYINT      NOT NULL DEFAULT 2      COMMENT '优先级 1=低 2=中 3=高',
    status           VARCHAR(20)  NOT NULL                COMMENT '状态: PENDING/IN_PROGRESS/CLOSED/REJECTED',
    submitter_id     BIGINT       NOT NULL                COMMENT '提交人 ID',
    assignee_id      BIGINT                               COMMENT '处理人 ID（派发后赋值）',
    resolution       TEXT                                 COMMENT '处理结论（关单时填写）',
    rejection_reason TEXT                                 COMMENT '驳回原因',
    created_at       DATETIME     NOT NULL                COMMENT '创建时间',
    assigned_at      DATETIME                             COMMENT '派发时间',
    closed_at        DATETIME                             COMMENT '关闭/驳回时间',
    PRIMARY KEY (id),
    INDEX idx_status (status),
    INDEX idx_assignee (assignee_id),
    INDEX idx_submitter (submitter_id)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COMMENT = '工单主表';
