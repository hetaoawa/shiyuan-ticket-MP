package top.hetao.shiyuanticketmp.webhook.sender;

import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import top.hetao.shiyuanticketmp.workorder.event.WorkOrderEvent;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * WebHook 消息聚合器。
 *
 * <p><b>核心策略：防打扰合并</b>
 * <ol>
 *   <li>收到事件后入队，不立即投递</li>
 *   <li>启动 10 秒倒计时</li>
 *   <li>若 10 秒内无新事件 → 到期投递</li>
 *   <li>若 10 秒内有新事件 → 重置倒计时（仍为 10 秒）</li>
 *   <li>倒计时到期时，一次性合并投递队列中所有事件</li>
 * </ol>
 *
 * <p>两个通道（DingTalk / CargoOwner）共享同一个聚合器，
 * 刷新时分别调用各自的 {@code dispatchBatch} 方法。
 */
@Component
public class WebhookMessageAggregator {

    private static final Logger log = LoggerFactory.getLogger(WebhookMessageAggregator.class);

    @Value("${webhook.aggregate-delay-seconds:10}")
    private int aggregateDelaySeconds;

    private final ConcurrentLinkedQueue<WorkOrderEvent> queue = new ConcurrentLinkedQueue<>();
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "webhook-aggregator");
        t.setDaemon(true);
        return t;
    });
    private final AtomicReference<ScheduledFuture<?>> pendingFlush = new AtomicReference<>();

    private final DingTalkDispatcher dingTalkDispatcher;
    private final CargoOwnerDispatcher cargoOwnerDispatcher;

    public WebhookMessageAggregator(DingTalkDispatcher dingTalkDispatcher,
                                     CargoOwnerDispatcher cargoOwnerDispatcher) {
        this.dingTalkDispatcher = dingTalkDispatcher;
        this.cargoOwnerDispatcher = cargoOwnerDispatcher;
    }

    /**
     * 提交事件到聚合队列。
     *
     * <p>每次提交都会重置倒计时：取消已有的 flush 任务，重新调度一个
     * 延迟 {@code aggregateDelaySeconds} 秒的新任务。
     */
    public void submit(WorkOrderEvent event) {
        queue.offer(event);
        log.info("[Aggregator] 事件入队 orderId={} type={} 队列大小={}",
                event.getWorkOrderId(), event.getStatus(), queue.size());

        // 取消旧的 flush 任务，重新计时
        ScheduledFuture<?> old = pendingFlush.getAndSet(null);
        if (old != null) {
            old.cancel(false);
        }

        ScheduledFuture<?> scheduled = scheduler.schedule(
                this::flush, aggregateDelaySeconds, TimeUnit.SECONDS);
        pendingFlush.set(scheduled);
    }

    /**
     * 刷新：按通道过滤后投递队列中所有事件。
     *
     * <p>根据事件的 {@code targetChannels} 字段过滤：
     * <ul>
     *   <li>DINGTALK → 仅投递到钉钉</li>
     *   <li>CARGO_OWNER → 仅投递到货主侧</li>
     *   <li>BOTH → 双通道都投递</li>
     * </ul>
     */
    private void flush() {
        pendingFlush.set(null);

        List<WorkOrderEvent> batch = new ArrayList<>();
        WorkOrderEvent event;
        while ((event = queue.poll()) != null) {
            batch.add(event);
        }

        if (batch.isEmpty()) {
            return;
        }

        log.info("[Aggregator] 开始刷新，批量投递 {} 条事件", batch.size());

        // 按通道过滤
        List<WorkOrderEvent> dingTalkEvents = batch.stream()
                .filter(e -> e.getTargetChannels() == ChannelTarget.DINGTALK
                        || e.getTargetChannels() == ChannelTarget.BOTH)
                .toList();

        List<WorkOrderEvent> cargoOwnerEvents = batch.stream()
                .filter(e -> e.getTargetChannels() == ChannelTarget.CARGO_OWNER
                        || e.getTargetChannels() == ChannelTarget.BOTH)
                .toList();

        // 钉钉通道投递
        if (!dingTalkEvents.isEmpty()) {
            try {
                log.info("[Aggregator] DingTalk 投递 {} 条", dingTalkEvents.size());
                dingTalkDispatcher.dispatchBatch(dingTalkEvents);
            } catch (Exception e) {
                log.error("[Aggregator] DingTalk 批量投递异常", e);
            }
        }

        // 货主侧通道投递
        if (!cargoOwnerEvents.isEmpty()) {
            try {
                log.info("[Aggregator] CargoOwner 投递 {} 条", cargoOwnerEvents.size());
                cargoOwnerDispatcher.dispatchBatch(cargoOwnerEvents);
            } catch (Exception e) {
                log.error("[Aggregator] CargoOwner 批量投递异常", e);
            }
        }
    }

    @PreDestroy
    public void shutdown() {
        log.info("[Aggregator] 关闭，刷新剩余 {} 条事件", queue.size());
        flush();
        scheduler.shutdown();
    }
}
