package top.hetao.shiyuanticketmp.workorder.cache;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import top.hetao.shiyuanticketmp.common.cache.RedisCacheHelper;
import top.hetao.shiyuanticketmp.common.context.TenantContext;
import top.hetao.shiyuanticketmp.workorder.entity.WorkOrder;

import java.util.concurrent.TimeUnit;

/**
 * 工单 Redis 缓存管理。
 *
 * <p>缓存策略：
 * <ul>
 *   <li>工单详情缓存 10 分钟</li>
 *   <li>状态变更时主动失效</li>
 *   <li>写入时主动刷新缓存</li>
 * </ul>
 */
@Component
public class WorkOrderCacheManager {

    private static final Logger log = LoggerFactory.getLogger(WorkOrderCacheManager.class);

    private static final String KEY_PREFIX = "workorder:";
    private static final long CACHE_TTL_MINUTES = 10;

    private final RedisCacheHelper cacheHelper;

    public WorkOrderCacheManager(RedisCacheHelper cacheHelper) {
        this.cacheHelper = cacheHelper;
    }

    /**
     * 缓存工单。
     */
    public void cacheWorkOrder(WorkOrder order) {
        Long tenantId = TenantContext.requireTenantId();
        if (order == null || order.getId() == null || !tenantId.equals(order.getTenantId())) {
            throw new IllegalArgumentException("只能缓存当前租户的有效工单");
        }
        String key = buildKey(tenantId, order.getId());
        cacheHelper.set(key, order, CACHE_TTL_MINUTES, TimeUnit.MINUTES);
        log.debug("[缓存] 工单已缓存 id={}", order.getId());
    }

    /**
     * 读取工单缓存。
     */
    public WorkOrder getWorkOrder(Long orderId) {
        Long tenantId = TenantContext.requireTenantId();
        String key = buildKey(tenantId, orderId);
        WorkOrder order = cacheHelper.get(key, WorkOrder.class);
        if (order != null && !tenantId.equals(order.getTenantId())) {
            log.warn("[缓存] 拒绝跨租户缓存值 key={} cachedTenant={}", key, order.getTenantId());
            cacheHelper.delete(key);
            return null;
        }
        return order;
    }

    /**
     * 失效工单缓存。
     */
    public void evictWorkOrder(Long orderId) {
        Long tenantId = TenantContext.requireTenantId();
        String key = buildKey(tenantId, orderId);
        cacheHelper.delete(key);
        log.debug("[缓存] 工单缓存已失效 id={}", orderId);
    }

    private String buildKey(Long tenantId, Long orderId) {
        if (orderId == null) {
            throw new IllegalArgumentException("工单 ID 不能为空");
        }
        return KEY_PREFIX + tenantId + ":" + orderId;
    }
}
