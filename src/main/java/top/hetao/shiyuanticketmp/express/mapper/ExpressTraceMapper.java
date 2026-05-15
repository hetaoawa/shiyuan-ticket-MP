package top.hetao.shiyuanticketmp.express.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import top.hetao.shiyuanticketmp.express.entity.ExpressTraceRecord;

/**
 * 物流轨迹落库 Mapper。
 */
@Mapper
public interface ExpressTraceMapper extends BaseMapper<ExpressTraceRecord> {
}
