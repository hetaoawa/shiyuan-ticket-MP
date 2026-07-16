-- 工单表增加货主侧群ID和发送人ID字段
ALTER TABLE work_order ADD COLUMN conversation_id VARCHAR(128) DEFAULT NULL COMMENT '货主侧群ID' AFTER closed_at;
ALTER TABLE work_order ADD COLUMN sender_staff_id VARCHAR(64) DEFAULT NULL COMMENT '货主侧发送人ID' AFTER conversation_id;
