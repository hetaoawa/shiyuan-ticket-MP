package top.hetao.shiyuanticketmp.workorder.comment.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import top.hetao.shiyuanticketmp.workorder.comment.entity.WorkOrderComment;
import top.hetao.shiyuanticketmp.workorder.comment.mapper.WorkOrderCommentMapper;
import top.hetao.shiyuanticketmp.workorder.exception.WorkOrderException;

import java.util.List;

/**
 * 工单评论业务服务。
 */
@Service
public class WorkOrderCommentService extends ServiceImpl<WorkOrderCommentMapper, WorkOrderComment> {

    /**
     * 添加评论。
     *
     * @param workOrderId 工单ID
     * @param content     评论内容
     * @param commenterId 评论人ID
     * @param commentType 评论类型（COMMENT/NOTE）
     * @param attachments 附件ID列表JSON
     * @return 创建的评论
     */
    @Transactional
    public WorkOrderComment addComment(Long workOrderId, String content, Long commenterId,
                                       String commentType, String attachments) {
        if (content == null || content.isBlank()) {
            throw new WorkOrderException("评论内容不能为空");
        }

        WorkOrderComment comment = new WorkOrderComment();
        comment.setWorkOrderId(workOrderId);
        comment.setContent(content);
        comment.setCommenterId(commenterId);
        comment.setCommentType(commentType != null ? commentType : "COMMENT");
        comment.setAttachments(attachments);
        save(comment);

        return comment;
    }

    /**
     * 查询工单的评论列表（分页）。
     *
     * @param workOrderId 工单ID
     * @param page        页码
     * @param pageSize    每页条数
     * @return 分页结果
     */
    @Transactional(readOnly = true)
    public IPage<WorkOrderComment> listByWorkOrder(Long workOrderId, int page, int pageSize) {
        LambdaQueryWrapper<WorkOrderComment> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(WorkOrderComment::getWorkOrderId, workOrderId)
               .orderByDesc(WorkOrderComment::getCreatedAt);
        return page(new Page<>(page, pageSize), wrapper);
    }

    /**
     * 查询工单的所有评论（不分页，用于导出等场景）。
     */
    @Transactional(readOnly = true)
    public List<WorkOrderComment> listAllByWorkOrder(Long workOrderId) {
        LambdaQueryWrapper<WorkOrderComment> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(WorkOrderComment::getWorkOrderId, workOrderId)
               .orderByAsc(WorkOrderComment::getCreatedAt);
        return list(wrapper);
    }
}
