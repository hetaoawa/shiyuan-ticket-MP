-- V2__create_webhook_dead_letter.sql
-- WebHook 死信记录表，全量重试失败后落库，供管理员后台手动补偿

CREATE TABLE IF NOT EXISTS webhook_dead_letter
(
    id          BIGINT        NOT NULL AUTO_INCREMENT COMMENT '主键',
    event_id    VARCHAR(64)   NOT NULL                COMMENT '原始事件 ID，与 Header X-Event-Id 一致',
    event_type  VARCHAR(64)   NOT NULL                COMMENT '事件类型，如 WORK_ORDER.CLOSED',
    target_url  VARCHAR(512)  NOT NULL                COMMENT '投递目标 URL',
    payload     MEDIUMTEXT    NOT NULL                COMMENT '原始请求体 JSON，补偿时直接重投',
    last_error  VARCHAR(1024)                         COMMENT '最后一次失败错误描述',
    attempts    INT           NOT NULL DEFAULT 0      COMMENT '累计尝试次数（含首次+重试）',
    status      VARCHAR(16)   NOT NULL DEFAULT 'PENDING' COMMENT '状态: PENDING/RESOLVED/IGNORED',
    created_at  DATETIME      NOT NULL                COMMENT '死信记录创建时间',
    resolved_at DATETIME                              COMMENT '管理员处理时间',
    resolved_by VARCHAR(64)                           COMMENT '处理管理员用户名',
    PRIMARY KEY (id),
    INDEX idx_status_created (status, created_at),
    INDEX idx_event_id (event_id)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COMMENT = 'WebHook 死信记录表';
