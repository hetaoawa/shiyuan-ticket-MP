package top.hetao.shiyuanticketmp.workorder.cache;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import top.hetao.shiyuanticketmp.common.cache.RedisCacheHelper;
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
        String key = buildKey(order.getId());
        cacheHelper.set(key, order, CACHE_TTL_MINUTES, TimeUnit.MINUTES);
        log.debug("[缓存] 工单已缓存 id={}", order.getId());
    }

    /**
     * 读取工单缓存。
     */
    public WorkOrder getWorkOrder(Long orderId) {
        String key = buildKey(orderId);
        return cacheHelper.get(key, WorkOrder.class);
    }

    /**
     * 失效工单缓存。
     */
    public void evictWorkOrder(Long orderId) {
        String key = buildKey(orderId);
        cacheHelper.delete(key);
        log.debug("[缓存] 工单缓存已失效 id={}", orderId);
    }

    private String buildKey(Long orderId) {
        return KEY_PREFIX + orderId;
    }
}
