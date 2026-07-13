package top.hetao.shiyuanticketmp.audit.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import top.hetao.shiyuanticketmp.audit.entity.SysAuditLog;
import top.hetao.shiyuanticketmp.audit.mapper.SysAuditLogMapper;
import top.hetao.shiyuanticketmp.common.context.TenantContext;
import top.hetao.shiyuanticketmp.tenant.service.TenantLifecycleGuard;

import java.time.LocalDateTime;

/**
 * 审计日志服务。
 */
@Service
public class AuditLogService extends ServiceImpl<SysAuditLogMapper, SysAuditLog> {

    private final TenantLifecycleGuard tenantLifecycleGuard;

    public AuditLogService(TenantLifecycleGuard tenantLifecycleGuard) {
        this.tenantLifecycleGuard = tenantLifecycleGuard;
    }

    /** Persists one asynchronous audit row behind the tenant lifecycle lock. */
    @Transactional
    public void saveForTenant(SysAuditLog auditLog) {
        if (auditLog == null || auditLog.getTenantId() == null) {
            throw new IllegalArgumentException("Audit log tenant id is required");
        }
        try (TenantContext.Scope ignored = TenantContext.useTenant(auditLog.getTenantId())) {
            tenantLifecycleGuard.lockTenantForDerivedWrite(auditLog.getTenantId());
            if (baseMapper.insert(auditLog) != 1) {
                throw new IllegalStateException("Audit log insert affected an unexpected row count");
            }
        }
    }

    /**
     * 分页查询审计日志。
     *
     * @param bizType 业务类型
     * @param bizId   业务ID（可选）
     * @param page    页码
     * @param pageSize 每页条数
     * @return 分页结果
     */
    public IPage<SysAuditLog> listPage(String bizType, Long bizId, int page, int pageSize,
                                       LocalDateTime createdStartTime, LocalDateTime createdEndTime) {
        LambdaQueryWrapper<SysAuditLog> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(SysAuditLog::getBizType, bizType);
        if (bizId != null) {
            wrapper.eq(SysAuditLog::getBizId, bizId);
        }
        if (createdStartTime != null) {
            wrapper.ge(SysAuditLog::getCreatedAt, createdStartTime);
        }
        if (createdEndTime != null) {
            wrapper.le(SysAuditLog::getCreatedAt, createdEndTime);
        }
        wrapper.orderByDesc(SysAuditLog::getCreatedAt);
        return page(new Page<>(page, pageSize), wrapper);
    }
}
