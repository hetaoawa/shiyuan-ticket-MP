package top.hetao.shiyuanticketmp.audit.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import top.hetao.shiyuanticketmp.audit.entity.SysAuditLog;
import top.hetao.shiyuanticketmp.audit.mapper.SysAuditLogMapper;
import top.hetao.shiyuanticketmp.audit.support.AuditLogPresentation;
import top.hetao.shiyuanticketmp.auth.entity.SysUser;
import top.hetao.shiyuanticketmp.auth.mapper.SysUserMapper;
import top.hetao.shiyuanticketmp.common.context.TenantContext;
import top.hetao.shiyuanticketmp.tenant.service.TenantLifecycleGuard;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 审计日志服务。
 */
@Service
public class AuditLogService extends ServiceImpl<SysAuditLogMapper, SysAuditLog> {

    private static final Logger log = LoggerFactory.getLogger(AuditLogService.class);
    private static final int MAX_TIMELINE_RECORDS = 500;

    private final TenantLifecycleGuard tenantLifecycleGuard;
    private final SysUserMapper userMapper;
    private final ObjectMapper objectMapper;

    public AuditLogService(TenantLifecycleGuard tenantLifecycleGuard,
                           SysUserMapper userMapper,
                           ObjectMapper objectMapper) {
        this.tenantLifecycleGuard = tenantLifecycleGuard;
        this.userMapper = userMapper;
        this.objectMapper = objectMapper;
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
        return listPage(bizType, bizId, null, null, page, pageSize,
                createdStartTime, createdEndTime);
    }

    /**
     * 分页查询并补齐审计展示字段。action/operatorId 均为可选过滤条件。
     */
    @Transactional(readOnly = true)
    public IPage<SysAuditLog> listPage(String bizType, Long bizId, String action, Long operatorId,
                                       int page, int pageSize,
                                       LocalDateTime createdStartTime, LocalDateTime createdEndTime) {
        LambdaQueryWrapper<SysAuditLog> wrapper = new LambdaQueryWrapper<>();
        if (bizType != null && !bizType.isBlank()) {
            wrapper.eq(SysAuditLog::getBizType, bizType.trim());
        }
        if (bizId != null) {
            wrapper.eq(SysAuditLog::getBizId, bizId);
        }
        if (action != null && !action.isBlank()) {
            wrapper.eq(SysAuditLog::getAction, action.trim().toUpperCase(Locale.ROOT));
        }
        if (operatorId != null) {
            wrapper.eq(SysAuditLog::getOperatorId, operatorId);
        }
        if (createdStartTime != null) {
            wrapper.ge(SysAuditLog::getCreatedAt, createdStartTime);
        }
        if (createdEndTime != null) {
            wrapper.le(SysAuditLog::getCreatedAt, createdEndTime);
        }
        wrapper.orderByDesc(SysAuditLog::getCreatedAt)
                .orderByDesc(SysAuditLog::getId);
        IPage<SysAuditLog> result = page(new Page<>(Math.max(page, 1), Math.min(Math.max(pageSize, 1), 100)), wrapper);
        enrichRecords(result.getRecords());
        return result;
    }

    /** 查询单个工单最新 500 个轨迹节点，响应内按时间正序排列。 */
    @Transactional(readOnly = true)
    public TimelineResult getWorkOrderTimeline(Long workOrderId) {
        if (workOrderId == null) {
            return new TimelineResult(Collections.emptyList(), 0);
        }
        LambdaQueryWrapper<SysAuditLog> countWrapper = new LambdaQueryWrapper<SysAuditLog>()
                .eq(SysAuditLog::getBizType, "WORK_ORDER")
                .eq(SysAuditLog::getBizId, workOrderId);
        long total = count(countWrapper);

        LambdaQueryWrapper<SysAuditLog> latestWrapper = new LambdaQueryWrapper<SysAuditLog>()
                .eq(SysAuditLog::getBizType, "WORK_ORDER")
                .eq(SysAuditLog::getBizId, workOrderId)
                .orderByDesc(SysAuditLog::getCreatedAt)
                .orderByDesc(SysAuditLog::getId)
                .last("LIMIT " + MAX_TIMELINE_RECORDS);
        List<SysAuditLog> records = new ArrayList<>(list(latestWrapper));
        Collections.reverse(records);
        enrichRecords(records);
        return new TimelineResult(records, total);
    }

    public record TimelineResult(List<SysAuditLog> records, long total) {
        public boolean truncated() {
            return total > records.size();
        }
    }

    void enrichRecords(List<SysAuditLog> records) {
        if (records == null || records.isEmpty()) {
            return;
        }
        Set<Long> operatorIds = records.stream()
                .filter(record -> record != null && record.getOperatorId() != null && record.getOperatorId() > 0)
                .map(SysAuditLog::getOperatorId)
                .collect(Collectors.toSet());
        Map<Long, SysUser> users = loadUsers(operatorIds);

        for (SysAuditLog record : records) {
            if (record == null) {
                continue;
            }
            Map<String, Object> detail = readDetail(record.getDetail());

            String actionLabel = AuditLogPresentation.text(detail, "actionLabel");
            record.setActionLabel(actionLabel.isBlank()
                    ? AuditLogPresentation.actionLabel(record.getAction()) : actionLabel);

            String operatorName = AuditLogPresentation.text(detail, "operatorName");
            if (operatorName.isBlank()) {
                operatorName = resolveOperatorName(record.getOperatorId(), users);
            }
            record.setOperatorName(operatorName);

            String source = AuditLogPresentation.sourceCode(
                    record.getAction(), detail, record.getOperatorId());
            record.setRequestSource(source);
            String sourceLabel = AuditLogPresentation.text(detail, "requestSourceLabel");
            record.setRequestSourceLabel(sourceLabel.isBlank()
                    ? AuditLogPresentation.sourceLabel(source) : sourceLabel);
            record.setDetailSummary(AuditLogPresentation.detailSummary(record.getAction(), detail));
        }
    }

    private Map<Long, SysUser> loadUsers(Collection<Long> operatorIds) {
        if (operatorIds == null || operatorIds.isEmpty()) {
            return Collections.emptyMap();
        }
        Map<Long, SysUser> users = new HashMap<>();
        try {
            users.putAll(userMapper.selectBatchIds(operatorIds).stream()
                    .collect(Collectors.toMap(SysUser::getId, Function.identity(), (left, right) -> left)));
        } catch (RuntimeException e) {
            log.warn("[审计] 批量补齐操作人信息失败，使用 ID 回退展示", e);
        }
        Long activeTenantId = TenantContext.getTenantId();
        operatorIds.stream().filter(id -> !users.containsKey(id)).forEach(id -> {
            try {
                SysUser user = userMapper.selectByIdIgnoreTenant(id);
                if (user != null && (activeTenantId == null
                        || activeTenantId.equals(user.getTenantId())
                        || Long.valueOf(0L).equals(user.getTenantId()))) {
                    users.put(id, user);
                }
            } catch (RuntimeException e) {
                log.debug("[审计] 操作人跨租户安全回填失败 userId={}", id, e);
            }
        });
        return users;
    }

    private Map<String, Object> readDetail(String detail) {
        if (detail == null || detail.isBlank()) {
            return Collections.emptyMap();
        }
        try {
            return objectMapper.readValue(detail, new TypeReference<>() { });
        } catch (Exception e) {
            return Collections.emptyMap();
        }
    }

    private String resolveOperatorName(Long operatorId, Map<Long, SysUser> users) {
        if (operatorId == null || operatorId == 0L) {
            return "系统";
        }
        SysUser user = users.get(operatorId);
        if (user != null) {
            if (user.getNickname() != null && !user.getNickname().isBlank()) {
                return user.getNickname();
            }
            if (user.getUsername() != null && !user.getUsername().isBlank()) {
                return user.getUsername();
            }
        }
        return "用户（ID: " + operatorId + "）";
    }
}
