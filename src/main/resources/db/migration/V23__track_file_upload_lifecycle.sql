-- Pending presigned uploads must not be visible as completed business files.
-- Existing rows predate the confirmation lifecycle and are preserved as confirmed.
ALTER TABLE sys_file
    ADD COLUMN upload_status VARCHAR(20) NULL COMMENT '上传状态：PENDING/CONFIRMED' AFTER download_url,
    ADD COLUMN confirmed_at DATETIME NULL COMMENT '服务端确认时间' AFTER upload_status,
    ADD COLUMN etag VARCHAR(255) NULL COMMENT '确认时对象 ETag' AFTER confirmed_at,
    ADD COLUMN object_version_id VARCHAR(255) NULL COMMENT '确认时对象版本 ID' AFTER etag;

UPDATE sys_file
SET upload_status = 'CONFIRMED',
    confirmed_at = COALESCE(updated_at, created_at, CURRENT_TIMESTAMP)
WHERE upload_status IS NULL;

ALTER TABLE sys_file
    MODIFY COLUMN upload_status VARCHAR(20) NOT NULL DEFAULT 'PENDING' COMMENT '上传状态：PENDING/CONFIRMED',
    ADD INDEX idx_sys_file_biz_status (biz_type, biz_id, upload_status);
