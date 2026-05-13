package top.hetao.shiyuanticketmp.audit.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;
import top.hetao.shiyuanticketmp.common.entity.BaseEntity;

/**
 * 操作审计日志实体，对应数据库表 {@code sys_audit_log}。
 *
 * <p>记录所有业务状态变更操作，用于追溯和审计。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("sys_audit_log")
public class SysAuditLog extends BaseEntity {

    /** 业务类型（如 WORK_ORDER） */
    private String bizType;

    /** 业务ID（如工单ID） */
    private Long bizId;

    /** 操作动作（CREATE/ASSIGN/CLOSE/REJECT） */
    private String action;

    /** 操作人ID */
    private Long operatorId;

    /** 操作详情（JSON） */
    private String detail;
}
