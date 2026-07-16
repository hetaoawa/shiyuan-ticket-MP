-- V4: 文件元数据表（记录上传到 S3/OSS 的文件信息）
CREATE TABLE IF NOT EXISTS sys_file (
    id              BIGINT          NOT NULL COMMENT '雪花ID',
    tenant_id       BIGINT          NOT NULL DEFAULT 0 COMMENT '租户ID',
    original_name   VARCHAR(500)    NOT NULL COMMENT '原始文件名',
    storage_key     VARCHAR(500)    NOT NULL COMMENT 'S3 存储 Key（含目录路径）',
    file_size       BIGINT          NOT NULL COMMENT '文件大小（字节）',
    content_type    VARCHAR(100)    DEFAULT NULL COMMENT 'MIME 类型',
    biz_type        VARCHAR(50)     DEFAULT NULL COMMENT '业务类型（如 WORK_ORDER_IMAGE）',
    biz_id          BIGINT          DEFAULT NULL COMMENT '关联业务ID（如工单ID）',
    uploader_id     BIGINT          NOT NULL COMMENT '上传人ID',
    download_url    VARCHAR(1000)   DEFAULT NULL COMMENT '下载地址（可选，缓存用）',
    version         INT             NOT NULL DEFAULT 1 COMMENT '乐观锁',
    created_at      DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    updated_at      DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    deleted         TINYINT         NOT NULL DEFAULT 0 COMMENT '逻辑删除',
    PRIMARY KEY (id),
    INDEX idx_tenant (tenant_id),
    INDEX idx_biz (biz_type, biz_id),
    INDEX idx_uploader (uploader_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='文件元数据表';
