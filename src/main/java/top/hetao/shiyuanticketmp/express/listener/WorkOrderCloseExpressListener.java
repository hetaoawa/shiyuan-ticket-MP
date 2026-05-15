package top.hetao.shiyuanticketmp.express.listener;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import top.hetao.shiyuanticketmp.express.ExpressService;
import top.hetao.shiyuanticketmp.workorder.entity.WorkOrder;
import top.hetao.shiyuanticketmp.workorder.event.WorkOrderStateChangedEvent;

/**
 * 工单完结事件监听器 — 自动获取物流信息并落库。
 *
 * <p>当工单状态变更为 CLOSED 时，若工单有物流单号，
 * 自动调用物流查询接口获取物流信息并存储到 express_trace 表。
 */
@Component
public class WorkOrderCloseExpressListener {

    private static final Logger log = LoggerFactory.getLogger(WorkOrderCloseExpressListener.class);

    private static final String ACTION_CLOSE = "CLOSE";

    private final ExpressService expressService;

    public WorkOrderCloseExpressListener(ExpressService expressService) {
        this.expressService = expressService;
    }

    @EventListener
    @Async("webhookExecutor")
    public void onWorkOrderClosed(WorkOrderStateChangedEvent event) {
        if (!ACTION_CLOSE.equals(event.getAction())) {
            return;
        }

        WorkOrder order = event.getWorkOrder();
        String trackingNo = order.getTrackingNo();

        if (trackingNo == null || trackingNo.isBlank()) {
            log.debug("[工单完结] 无物流单号，跳过物流查询 orderId={}", order.getId());
            return;
        }

        log.info("[工单完结] 开始获取物流信息 orderId={} trackingNo={}", order.getId(), trackingNo);
        expressService.fetchAndSaveOnClose(trackingNo);
    }
}
