package top.hetao.shiyuanticketmp.tenant.service;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import top.hetao.shiyuanticketmp.tenant.mapper.SysTenantMapper;
import top.hetao.shiyuanticketmp.workorder.exception.WorkOrderException;

/**
 * Database-backed lifecycle gate shared by every tenant-scoped persistent write.
 *
 * <p>The shared row lock is held until the caller transaction completes. Tenant disable/delete
 * take {@code FOR UPDATE} on the same row, so all paths use tenant row first, business row second.
 * Existing writers finish before deletion counts; new business writers block and then fail the
 * enabled-row predicate after disable/delete commits. Derived writes from already committed events
 * use a not-deleted predicate so disabling a tenant cannot discard audit/dead-letter evidence.
 * MySQL {@code LOCK IN SHARE MODE} supports both 5.7 and 8.</p>
 */
@Service
public class TenantLifecycleGuard {

    private final SysTenantMapper tenantMapper;

    public TenantLifecycleGuard(SysTenantMapper tenantMapper) {
        this.tenantMapper = tenantMapper;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void lockWritableTenant(Long tenantId) {
        validateTenantId(tenantId);
        if (tenantMapper.selectEnabledByIdForShare(tenantId) == null) {
            throw new WorkOrderException("租户不存在、已停用或已删除: " + tenantId);
        }
    }

    /** Locks a retained tenant for audit/dead-letter writes derived from a committed event. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void lockTenantForDerivedWrite(Long tenantId) {
        validateTenantId(tenantId);
        if (tenantMapper.selectNotDeletedByIdForShare(tenantId) == null) {
            throw new WorkOrderException("租户不存在或已删除: " + tenantId);
        }
    }

    private static void validateTenantId(Long tenantId) {
        if (tenantId == null || tenantId < 0) {
            throw new WorkOrderException("租户 ID 非法");
        }
    }
}
