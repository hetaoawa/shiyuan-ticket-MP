package top.hetao.shiyuanticketmp.audit.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import org.springframework.stereotype.Service;
import top.hetao.shiyuanticketmp.audit.entity.SysAuditLog;
import top.hetao.shiyuanticketmp.audit.mapper.SysAuditLogMapper;

/**
 * 审计日志服务。
 */
@Service
public class AuditLogService extends ServiceImpl<SysAuditLogMapper, SysAuditLog> {

    /**
     * 分页查询审计日志。
     *
     * @param bizType 业务类型
     * @param bizId   业务ID（可选）
     * @param page    页码
     * @param pageSize 每页条数
     * @return 分页结果
     */
    public IPage<SysAuditLog> listPage(String bizType, Long bizId, int page, int pageSize) {
        LambdaQueryWrapper<SysAuditLog> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(SysAuditLog::getBizType, bizType);
        if (bizId != null) {
            wrapper.eq(SysAuditLog::getBizId, bizId);
        }
        wrapper.orderByDesc(SysAuditLog::getCreatedAt);
        return page(new Page<>(page, pageSize), wrapper);
    }
}
