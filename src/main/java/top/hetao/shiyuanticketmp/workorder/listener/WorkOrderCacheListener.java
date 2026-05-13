package top.hetao.shiyuanticketmp.workorder.listener;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import top.hetao.shiyuanticketmp.workorder.cache.WorkOrderCacheManager;
import top.hetao.shiyuanticketmp.workorder.entity.WorkOrder;
import top.hetao.shiyuanticketmp.workorder.event.WorkOrderStateChangedEvent;

/**
 * 工单状态变更事件监听器 — 缓存失效。
 *
 * <p>状态变更后主动清除缓存，确保下次读取到最新数据。
 * 同时写入最新数据到缓存（write-through）。
 */
@Component
public class WorkOrderCacheListener {

    private static final Logger log = LoggerFactory.getLogger(WorkOrderCacheListener.class);

    private final WorkOrderCacheManager cacheManager;

    public WorkOrderCacheListener(WorkOrderCacheManager cacheManager) {
        this.cacheManager = cacheManager;
    }

    @EventListener
    @Async("webhookExecutor")
    public void onWorkOrderStateChanged(WorkOrderStateChangedEvent event) {
        WorkOrder order = event.getWorkOrder();

        // 先清除旧缓存
        cacheManager.evictWorkOrder(order.getId());

        // 写入最新数据（write-through）
        cacheManager.cacheWorkOrder(order);

        log.info("[缓存] 工单缓存已更新 id={} action={}", order.getId(), event.getAction());
    }
}
