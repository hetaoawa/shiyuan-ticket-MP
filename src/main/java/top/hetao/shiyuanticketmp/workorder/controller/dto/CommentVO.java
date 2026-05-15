package top.hetao.shiyuanticketmp.workorder.controller.dto;

import lombok.Data;
import top.hetao.shiyuanticketmp.workorder.comment.entity.WorkOrderComment;

import java.time.LocalDateTime;

/**
 * 评论视图对象，包含评论信息和用户信息。
 */
@Data
public class CommentVO {

    private Long id;
    private Long workOrderId;
    private String content;
    private Long commenterId;
    private String commentType;
    private String attachments;
    private LocalDateTime createdAt;

    /** 评论人用户名 */
    private String username;

    /** 评论人昵称 */
    private String nickname;

    public static CommentVO from(WorkOrderComment comment, String username, String nickname) {
        CommentVO vo = new CommentVO();
        vo.setId(comment.getId());
        vo.setWorkOrderId(comment.getWorkOrderId());
        vo.setContent(comment.getContent());
        vo.setCommenterId(comment.getCommenterId());
        vo.setCommentType(comment.getCommentType());
        vo.setAttachments(comment.getAttachments());
        vo.setCreatedAt(comment.getCreatedAt());
        vo.setUsername(username);
        vo.setNickname(nickname);
        return vo;
    }
}
