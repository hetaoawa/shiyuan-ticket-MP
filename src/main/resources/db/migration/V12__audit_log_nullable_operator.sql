-- V12: 修复 sys_audit_log.operator_id 允许为空（系统操作无明确操作人）

ALTER TABLE sys_audit_log MODIFY COLUMN operator_id BIGINT NULL COMMENT '操作人ID（系统操作可为空）';
