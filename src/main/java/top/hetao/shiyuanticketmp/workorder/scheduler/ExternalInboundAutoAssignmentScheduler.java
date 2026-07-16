package top.hetao.shiyuanticketmp.workorder.scheduler;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.SchedulingConfigurer;
import org.springframework.scheduling.config.ScheduledTaskRegistrar;
import org.springframework.stereotype.Component;
import top.hetao.shiyuanticketmp.common.context.TenantContext;
import top.hetao.shiyuanticketmp.tenant.service.TenantService;
import top.hetao.shiyuanticketmp.workorder.entity.WorkOrder;
import top.hetao.shiyuanticketmp.workorder.exception.WorkOrderException;
import top.hetao.shiyuanticketmp.workorder.mapper.WorkOrderMapper;
import top.hetao.shiyuanticketmp.workorder.service.WorkOrderService;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;

/**
 * Finds old, untouched external inbound orders and atomically assigns them to
 * the owning tenant's cloud-warehouse administrator role.
 */
@Component
public class ExternalInboundAutoAssignmentScheduler implements SchedulingConfigurer {

    private static final Logger log = LoggerFactory.getLogger(ExternalInboundAutoAssignmentScheduler.class);
    private static final long REQUIRED_MINIMUM_AGE_SECONDS = 60L;
    private static final long MINIMUM_FIXED_DELAY_MS = 1000L;
    private static final int MAX_BATCH_SIZE = 1000;

    private final WorkOrderMapper mapper;
    private final WorkOrderService workOrderService;
    private final TenantService tenantService;
    private final boolean enabled;
    private final long fixedDelayMs;
    private final long minimumAgeSeconds;
    private final int batchSize;

    public ExternalInboundAutoAssignmentScheduler(
            WorkOrderMapper mapper,
            WorkOrderService workOrderService,
            TenantService tenantService,
            @Value("${workorder.external-auto-assignment.enabled:true}") boolean enabled,
            @Value("${workorder.external-auto-assignment.fixed-delay-ms:10000}") long fixedDelayMs,
            @Value("${workorder.external-auto-assignment.minimum-age-seconds:60}") long minimumAgeSeconds,
            @Value("${workorder.external-auto-assignment.batch-size:100}") int batchSize) {
        this.mapper = mapper;
        this.workOrderService = workOrderService;
        this.tenantService = tenantService;
        this.enabled = enabled;
        this.fixedDelayMs = Math.max(MINIMUM_FIXED_DELAY_MS, fixedDelayMs);
        this.minimumAgeSeconds = Math.max(REQUIRED_MINIMUM_AGE_SECONDS, minimumAgeSeconds);
        this.batchSize = Math.max(1, Math.min(MAX_BATCH_SIZE, batchSize));
    }

    @Override
    public void configureTasks(ScheduledTaskRegistrar taskRegistrar) {
        if (enabled) {
            taskRegistrar.addFixedDelayTask(this::assignEligibleOrders, Duration.ofMillis(fixedDelayMs));
        }
    }

    public void assignEligibleOrders() {
        // Scheduled executor threads are reusable. Start and finish without a
        // tenant to prevent stale ThreadLocal state from broadening a scan.
        TenantContext.clear();
        try {
            if (!enabled) {
                return;
            }
            List<WorkOrder> candidates;
            LocalDateTime cutoff = LocalDateTime.now().minusSeconds(minimumAgeSeconds);
            try (TenantContext.Scope ignored = TenantContext.useInternalBypass()) {
                candidates = mapper.selectExternalAutoAssignmentCandidates(cutoff, batchSize);
            }

            for (WorkOrder candidate : candidates) {
                processCandidate(candidate);
            }
        } catch (Exception e) {
            log.error("[工单自动派发] 候选扫描失败", e);
        } finally {
            TenantContext.clear();
        }
    }

    private void processCandidate(WorkOrder candidate) {
        if (candidate == null || candidate.getId() == null
                || candidate.getTenantId() == null || candidate.getTenantId() <= 0) {
            log.error("[工单自动派发] 候选数据缺少有效租户或工单ID tenantId={} orderId={}",
                    candidate == null ? null : candidate.getTenantId(),
                    candidate == null ? null : candidate.getId());
            return;
        }

        Long tenantId = candidate.getTenantId();
        Long orderId = candidate.getId();
        try (TenantContext.Scope ignored = TenantContext.useTenant(tenantId)) {
            tenantService.requireEnabled(tenantId);
            workOrderService.autoAssignExternalInbound(orderId, tenantId);
        } catch (WorkOrderException e) {
            // Tenant disabled / role missing are operator-actionable setup
            // problems. Keep the row untouched and avoid a stack trace storm.
            log.warn("[工单自动派发] 永久业务条件不满足 tenantId={} orderId={} message={}",
                    tenantId, orderId, e.getMessage());
        } catch (Exception e) {
            // Transient infrastructure failures are retried by the next scan.
            log.error("[工单自动派发] 单工单处理异常 tenantId={} orderId={} message={}",
                    tenantId, orderId, e.getMessage(), e);
        }
    }
}
