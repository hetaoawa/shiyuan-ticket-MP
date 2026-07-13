-- V11: 创建物流轨迹落库表

CREATE TABLE IF NOT EXISTS express_trace (
    id              BIGINT          NOT NULL,
    tenant_id       BIGINT          NOT NULL DEFAULT 0,
    tracking_no     VARCHAR(64)     NOT NULL COMMENT '物流单号',
    cp_code         VARCHAR(32)     COMMENT '快递公司编码',
    logistics_status VARCHAR(32)    COMMENT '物流状态码（ACCEPT/TRANSPORT/DELIVERING/DELIVERED）',
    logistics_status_desc VARCHAR(64) COMMENT '物流状态描述',
    response_json   MEDIUMTEXT      NOT NULL COMMENT '完整物流响应 JSON',
    fetched_at      DATETIME        NOT NULL COMMENT '获取时间',
    expires_at      DATETIME        COMMENT '过期时间（已签收为 NULL，表示永不过期）',
    created_at      DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at      DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    deleted         TINYINT         NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    UNIQUE INDEX uk_tracking_no (tracking_no),
    INDEX idx_expires_at (expires_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='物流轨迹落库表';
