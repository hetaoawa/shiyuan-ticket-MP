CREATE TABLE work_order_batch_request (
    id                  BIGINT       NOT NULL,
    tenant_id           BIGINT       NOT NULL,
    requester_id        BIGINT       NOT NULL,
    idempotency_key     VARCHAR(64)  NOT NULL,
    request_hash        CHAR(64)     NOT NULL,
    work_order_ids_json TEXT         NULL,
    created_at          DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at          DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    deleted             TINYINT      NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    UNIQUE KEY uk_work_order_batch_idempotency (tenant_id, requester_id, idempotency_key),
    KEY idx_work_order_batch_created_at (created_at)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = 'work order batch idempotency requests';
