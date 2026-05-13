package top.hetao.shiyuanticketmp.audit.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import top.hetao.shiyuanticketmp.audit.entity.SysAuditLog;

/**
 * 审计日志 Mapper。
 */
@Mapper
public interface SysAuditLogMapper extends BaseMapper<SysAuditLog> {
}
