-- V7__add_tracking_and_address.sql
-- 工单表增加物流单号和目标地址字段

ALTER TABLE work_order
    ADD COLUMN tracking_no VARCHAR(50) COMMENT '物流单号' AFTER description,
    ADD COLUMN target_address VARCHAR(500) COMMENT '目标地址' AFTER tracking_no;

CREATE INDEX idx_tracking_no ON work_order (tracking_no);
