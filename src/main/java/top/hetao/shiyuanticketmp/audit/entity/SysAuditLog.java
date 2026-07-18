package top.hetao.shiyuanticketmp.audit.entity;

import com.baomidou.mybatisplus.annotation.TableField;
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

    // ---- 以下为查询展示字段，不映射数据库 ----

    /** 可读动作名称，例如“创建工单”。 */
    @TableField(exist = false)
    private String actionLabel;

    /** 操作人昵称/用户名；系统任务统一展示为“系统”。 */
    @TableField(exist = false)
    private String operatorName;

    /** 从结构化详情生成的单行摘要。 */
    @TableField(exist = false)
    private String detailSummary;

    /** 请求来源代码：WEB、WEBHOOK 或 SYSTEM。 */
    @TableField(exist = false)
    private String requestSource;

    /** 可读请求来源名称。 */
    @TableField(exist = false)
    private String requestSourceLabel;
}
