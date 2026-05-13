package top.hetao.shiyuanticketmp.workorder.comment.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;
import top.hetao.shiyuanticketmp.common.entity.BaseEntity;

/**
 * 工单评论/备注实体，对应数据库表 {@code work_order_comment}。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("work_order_comment")
public class WorkOrderComment extends BaseEntity {

    /** 工单ID */
    private Long workOrderId;

    /** 评论内容 */
    private String content;

    /** 评论人ID */
    private Long commenterId;

    /** 评论类型：COMMENT=评论 NOTE=备注 */
    @TableField("comment_type")
    private String commentType;

    /** 附件ID列表JSON，如 [1,2,3] */
    private String attachments;
}
