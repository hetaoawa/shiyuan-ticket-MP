package top.hetao.shiyuanticketmp.workorder.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import top.hetao.shiyuanticketmp.workorder.entity.WorkOrder;

/**
 * 工单 Mapper，继承 MyBatis-Plus BaseMapper 获得通用 CRUD 能力。
 *
 * <p>BaseMapper 提供的方法包括：insert、deleteById、updateById、selectById、selectList 等。
 * 复杂查询可使用 MyBatis-Plus 的 Wrapper 条件构造器，或保留自定义注解 SQL。
 *
 * <p>原有注解式 SQL 已移除，统一使用 BaseMapper 的通用方法。
 * 后续如需复杂查询（多表 JOIN、子查询），可在此接口中添加自定义方法。
 */
@Mapper
public interface WorkOrderMapper extends BaseMapper<WorkOrder> {
}
