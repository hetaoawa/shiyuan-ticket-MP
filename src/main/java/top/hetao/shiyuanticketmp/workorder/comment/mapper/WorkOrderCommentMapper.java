package top.hetao.shiyuanticketmp.workorder.comment.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import top.hetao.shiyuanticketmp.workorder.comment.entity.WorkOrderComment;

/**
 * 工单评论 Mapper，继承 MyBatis-Plus BaseMapper。
 */
@Mapper
public interface WorkOrderCommentMapper extends BaseMapper<WorkOrderComment> {
}
