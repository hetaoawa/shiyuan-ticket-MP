package top.hetao.shiyuanticketmp.webhook.receiver;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import org.springframework.beans.factory.annotation.Value;
import top.hetao.shiyuanticketmp.webhook.receiver.handler.WebhookEventRouter;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * WebHook 事件后台消费 Worker。
 *
 * <p>持续从 Redis 队列中弹出事件并处理，支持优雅关闭。
 *
 * <p><b>扩展点：</b>当前实现仅解析事件并打印日志，
 * 后续可接入具体业务处理器（如工单状态同步、消息通知等）。
 */
@Component
public class WebhookEventWorker {

    private static final Logger log = LoggerFactory.getLogger(WebhookEventWorker.class);

    /** 连续异常时的最大退避时间（毫秒） */
    private static final long MAX_BACKOFF_MS = 30_000L;
    /** 连续异常计数达到阈值后打印 WARN 而非 ERROR，防止日志洪水 */
    private static final int WARN_THRESHOLD = 5;

    /** 启动延迟（毫秒），等待 Redis/DB 就绪 */
    @Value("${webhook.worker.startup-delay-ms:5000}")
    private long startupDelayMs;

    private final WebhookEventQueueService queueService;
    private final ObjectMapper objectMapper;
    private final WebhookEventRouter router;

    private final AtomicBoolean running = new AtomicBoolean(true);
    private ExecutorService executor;

    public WebhookEventWorker(WebhookEventQueueService queueService,
                              ObjectMapper objectMapper,
                              WebhookEventRouter router) {
        this.queueService = queueService;
        this.objectMapper = objectMapper;
        this.router = router;
    }

    @PostConstruct
    public void start() {
        executor = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "webhook-event-worker");
            t.setDaemon(true);
            return t;
        });
        executor.submit(this::runWithStartupDelay);
        log.info("[WebHook Worker] 已提交启动任务, {}ms后开始消费", startupDelayMs);
    }

    private void runWithStartupDelay() {
        sleep(startupDelayMs);
        log.info("[WebHook Worker] 开始消费事件");
        run();
    }

    @PreDestroy
    public void stop() {
        running.set(false);
        if (executor != null) {
            executor.shutdown();
        }
        log.info("[WebHook Worker] 已停止");
    }

    private void run() {
        int consecutiveErrors = 0;

        while (running.get()) {
            try {
                String eventJson = queueService.popBlocking();
                if (eventJson == null) {
                    // 超时，正常情况，重置错误计数
                    consecutiveErrors = 0;
                    continue;
                }
                processEvent(eventJson);
                queueService.ack(eventJson);
                consecutiveErrors = 0; // 处理成功，重置计数
            } catch (Exception e) {
                if (!running.get()) {
                    break;
                }

                consecutiveErrors++;
                long backoff = Math.min(1000L * (1L << Math.min(consecutiveErrors - 1, 4)), MAX_BACKOFF_MS);

                if (consecutiveErrors <= WARN_THRESHOLD) {
                    log.error("[WebHook Worker] 处理异常 (连续第{}次), {}ms后重试", consecutiveErrors, backoff, e);
                } else {
                    // 超过阈值后降级为 WARN，防止日志洪水；每 10 次打印一次完整堆栈
                    if (consecutiveErrors % 10 == 0) {
                        log.warn("[WebHook Worker] 持续异常 (连续第{}次), {}ms后重试", consecutiveErrors, backoff, e);
                    } else {
                        log.warn("[WebHook Worker] 持续异常 (连续第{}次), {}ms后重试, error={}",
                                consecutiveErrors, backoff, e.getMessage());
                    }
                }

                sleep(backoff);
            }
        }
    }

    private void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * 处理单个 WebHook 事件。
     *
     * <p>解析 JSON 后交由 {@link WebhookEventRouter} 路由到具体处理器。
     * 未注册 eventType 的事件仅记录日志，不抛异常（避免无意义重试）。
     */
    private void processEvent(String eventJson) {
        try {
            JsonNode root = objectMapper.readTree(eventJson);
            router.route(root);
        } catch (RuntimeException e) {
            log.error("[WebHook Worker] 事件处理失败: {}", eventJson, e);
            throw e;
        } catch (Exception e) {
            log.error("[WebHook Worker] 事件处理失败: {}", eventJson, e);
            throw new RuntimeException("事件处理异常", e);
        }
    }
}
