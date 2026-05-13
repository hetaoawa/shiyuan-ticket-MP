package top.hetao.shiyuanticketmp.workorder.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;
import top.hetao.shiyuanticketmp.common.entity.BaseEntity;
import top.hetao.shiyuanticketmp.workorder.enums.WorkOrderStatus;
import top.hetao.shiyuanticketmp.workorder.enums.WorkOrderType;

import java.time.LocalDateTime;

/**
 * 工单实体，对应数据库表 {@code work_order}。
 *
 * <p>继承 {@link BaseEntity} 获得雪花 ID、tenant_id、version、createdAt、updatedAt、deleted 等公共字段。
 * 业务逻辑勿在此类中添加，统一在 Service 层处理。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("work_order")
public class WorkOrder extends BaseEntity {

    /** 工单标题 */
    private String title;

    /** 工单描述 */
    private String description;

    /** 物流单号 */
    private String trackingNo;

    /** 目标地址 */
    private String targetAddress;

    /** 工单类型（自动解析） */
    @TableField("type")
    private WorkOrderType type;

    /** 优先级（1=低 2=中 3=高） */
    private Integer priority;

    /** 工单当前状态 */
    @TableField("status")
    private WorkOrderStatus status;

    /** 提交人 ID */
    private Long submitterId;

    /** 处理人 ID（派发后赋值） */
    private Long assigneeId;

    /** 处理结论（关单时填写） */
    private String resolution;

    /** 驳回原因（驳回时填写） */
    private String rejectionReason;

    /** 派发时间 */
    private LocalDateTime assignedAt;

    /** 关闭/驳回时间 */
    private LocalDateTime closedAt;
}
