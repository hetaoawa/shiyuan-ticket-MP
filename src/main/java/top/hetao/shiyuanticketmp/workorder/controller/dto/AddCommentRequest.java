package top.hetao.shiyuanticketmp.workorder.controller.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

/**
 * 添加评论请求体。
 */
@Data
public class AddCommentRequest {

    /** 评论内容（必填） */
    private String content;

    /** 评论类型：COMMENT=评论 NOTE=备注，默认 COMMENT */
    @JsonProperty("comment_type")
    private String commentType;

    /** 附件ID列表JSON，如 [1,2,3]（可选） */
    private String attachments;
}
