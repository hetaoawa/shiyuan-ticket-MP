package top.hetao.shiyuanticketmp.workorder.comment.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import top.hetao.shiyuanticketmp.auth.entity.SysUser;
import top.hetao.shiyuanticketmp.auth.mapper.SysUserMapper;
import top.hetao.shiyuanticketmp.workorder.comment.entity.WorkOrderComment;
import top.hetao.shiyuanticketmp.workorder.comment.mapper.WorkOrderCommentMapper;
import top.hetao.shiyuanticketmp.workorder.controller.dto.CommentVO;
import top.hetao.shiyuanticketmp.workorder.entity.WorkOrder;
import top.hetao.shiyuanticketmp.workorder.event.WorkOrderCommentEvent;
import top.hetao.shiyuanticketmp.workorder.exception.WorkOrderException;
import top.hetao.shiyuanticketmp.workorder.mapper.WorkOrderMapper;

import java.util.ArrayList;
import java.util.List;

/**
 * 工单评论业务服务。
 */
@Service
public class WorkOrderCommentService extends ServiceImpl<WorkOrderCommentMapper, WorkOrderComment> {

    private final SysUserMapper sysUserMapper;
    private final WorkOrderMapper workOrderMapper;
    private final ApplicationEventPublisher eventPublisher;

    public WorkOrderCommentService(SysUserMapper sysUserMapper, WorkOrderMapper workOrderMapper,
                                   ApplicationEventPublisher eventPublisher) {
        this.sysUserMapper = sysUserMapper;
        this.workOrderMapper = workOrderMapper;
        this.eventPublisher = eventPublisher;
    }

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

        // 发布评论事件（用于 WebHook 推送）
        WorkOrder workOrder = workOrderMapper.selectById(workOrderId);
        if (workOrder != null) {
            eventPublisher.publishEvent(new WorkOrderCommentEvent(this, workOrder, comment));
        }

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
     * 查询工单的评论列表（分页），包含用户信息。
     *
     * @param workOrderId 工单ID
     * @param page        页码
     * @param pageSize    每页条数
     * @return 包含用户信息的分页结果
     */
    @Transactional(readOnly = true)
    public IPage<CommentVO> listByWorkOrderWithUser(Long workOrderId, int page, int pageSize) {
        IPage<WorkOrderComment> commentPage = listByWorkOrder(workOrderId, page, pageSize);

        List<CommentVO> voList = new ArrayList<>();
        for (WorkOrderComment comment : commentPage.getRecords()) {
            SysUser user = sysUserMapper.selectByIdIgnoreTenant(comment.getCommenterId());
            String username = user != null ? user.getUsername() : null;
            String nickname = user != null ? user.getNickname() : null;
            voList.add(CommentVO.from(comment, username, nickname));
        }

        Page<CommentVO> voPage = new Page<>(commentPage.getCurrent(), commentPage.getSize(), commentPage.getTotal());
        voPage.setRecords(voList);
        return voPage;
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
