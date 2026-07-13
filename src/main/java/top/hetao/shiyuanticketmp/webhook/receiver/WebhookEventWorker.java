package top.hetao.shiyuanticketmp.webhook.receiver;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import top.hetao.shiyuanticketmp.webhook.receiver.handler.WebhookEventRouter;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/** Consumes webhook records with at-least-once delivery semantics. */
@Component
public class WebhookEventWorker {

    private static final Logger log = LoggerFactory.getLogger(WebhookEventWorker.class);
    private static final long MAX_INFRASTRUCTURE_BACKOFF_MS = 30_000L;
    private static final int WARN_THRESHOLD = 5;

    @Value("${webhook.worker.startup-delay-ms:5000}")
    private long startupDelayMs = 5_000L;

    @Value("${webhook.worker.lease-heartbeat-interval-ms:5000}")
    private long leaseHeartbeatIntervalMs = 5_000L;

    @Value("${webhook.worker.lease-timeout-ms:30000}")
    private long leaseTimeoutMs = 30_000L;

    @Value("${webhook.worker.shutdown-grace-ms:10000}")
    private long shutdownGraceMs = 10_000L;

    private final WebhookEventQueueService queueService;
    private final ObjectMapper objectMapper;
    private final WebhookEventRouter router;
    private final AtomicBoolean accepting = new AtomicBoolean(true);
    private final AtomicBoolean destroyed = new AtomicBoolean(false);
    private final Object lifecycleMonitor = new Object();
    private ExecutorService executor;
    private ScheduledExecutorService leaseExecutor;

    public WebhookEventWorker(WebhookEventQueueService queueService,
                              ObjectMapper objectMapper,
                              WebhookEventRouter router) {
        this.queueService = queueService;
        this.objectMapper = objectMapper;
        this.router = router;
    }

    @PostConstruct
    public void start() {
        ensureLeaseExecutor();
        executor = Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(runnable, "webhook-event-worker");
            thread.setDaemon(true);
            return thread;
        });
        executor.submit(this::runWithStartupDelay);
        log.info("[Webhook worker] scheduled startup in {}ms consumer={}",
                startupDelayMs, queueService.consumerName());
    }

    @PreDestroy
    public void stop() {
        accepting.set(false);
        boolean forced = false;
        if (executor != null) {
            executor.shutdown();
            try {
                if (!executor.awaitTermination(Math.max(0L, shutdownGraceMs), TimeUnit.MILLISECONDS)) {
                    forced = true;
                }
            } catch (InterruptedException e) {
                forced = true;
                Thread.currentThread().interrupt();
            }
        }
        synchronized (lifecycleMonitor) {
            destroyed.set(true);
        }
        if (forced && executor != null) {
            executor.shutdownNow();
        }
        if (leaseExecutor != null) {
            leaseExecutor.shutdownNow();
        }
        log.info("[Webhook worker] stopped consumer={} forced={}", queueService.consumerName(), forced);
    }

    private void runWithStartupDelay() {
        sleep(startupDelayMs);
        if (accepting.get()) {
            runLoop();
        }
    }

    private void runLoop() {
        int infrastructureErrors = 0;
        while (accepting.get()) {
            WebhookEventRecord event = null;
            boolean processingStarted = false;
            try {
                event = queueService.claimNext();
                if (event == null) {
                    infrastructureErrors = 0;
                    continue;
                }
                if (!accepting.get()) {
                    log.info("[Webhook worker] shutdown raced with claim; leaving record pending recordId={}",
                            event.recordId());
                    continue;
                }
                processingStarted = true;
                if (queueService.hasExceededAttemptLimit(event)) {
                    boolean moved = queueService.fail(event, null);
                    log.warn("[Webhook worker] pending record exceeded attempt limit eventId={} attempt={} deadLettered={}",
                            event.eventId(), event.deliveryAttempt(), moved);
                    continue;
                }
                processClaimed(event);
                infrastructureErrors = 0;
            } catch (Exception failure) {
                if (destroyed.get()) {
                    break;
                }
                if (event != null && processingStarted) {
                    handleEventFailure(event, failure);
                    infrastructureErrors = 0;
                } else if (!accepting.get()) {
                    break;
                } else {
                    infrastructureErrors++;
                    logInfrastructureFailure(infrastructureErrors, failure);
                    sleep(infrastructureBackoff(infrastructureErrors));
                }
            }
        }
    }

    /** Processes one already-claimed record and ACKs only after the router returns successfully. */
    void processClaimed(WebhookEventRecord event) {
        LeaseHeartbeat heartbeat = startLeaseHeartbeat(event);
        try {
            JsonNode root = objectMapper.readTree(event.payload());
            router.route(root);
            synchronized (lifecycleMonitor) {
                if (!destroyed.get() && (heartbeat == null || heartbeat.owned().get()) && !queueService.ack(event)) {
                    log.warn("[Webhook worker] ACK skipped because ownership changed eventId={} recordId={}",
                            event.eventId(), event.recordId());
                }
            }
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new RuntimeException("Webhook event processing failed", e);
        } finally {
            if (heartbeat != null) {
                heartbeat.future().cancel(false);
            }
        }
    }

    private LeaseHeartbeat startLeaseHeartbeat(WebhookEventRecord event) {
        synchronized (lifecycleMonitor) {
            if (destroyed.get()) {
                return null;
            }
            AtomicBoolean owned = new AtomicBoolean(true);
            long configured = Math.max(1L, leaseHeartbeatIntervalMs);
            long safeMaximum = Math.max(1L, Math.max(1L, leaseTimeoutMs) / 3L);
            long interval = Math.min(configured, safeMaximum);
            ScheduledFuture<?> future = ensureLeaseExecutor().scheduleAtFixedRate(() -> {
                if (destroyed.get() || !owned.get()) {
                    return;
                }
                try {
                    if (!queueService.renewLease(event)) {
                        owned.set(false);
                        log.warn("[Webhook worker] lease ownership lost eventId={} recordId={}",
                                event.eventId(), event.recordId());
                    }
                } catch (Exception e) {
                    log.error("[Webhook worker] lease heartbeat failed eventId={} recordId={}",
                            event.eventId(), event.recordId(), e);
                }
            }, 0L, interval, TimeUnit.MILLISECONDS);
            return new LeaseHeartbeat(future, owned);
        }
    }

    private synchronized ScheduledExecutorService ensureLeaseExecutor() {
        if (leaseExecutor == null || leaseExecutor.isShutdown()) {
            leaseExecutor = Executors.newSingleThreadScheduledExecutor(runnable -> {
                Thread thread = new Thread(runnable, "webhook-event-lease-heartbeat");
                thread.setDaemon(true);
                return thread;
            });
        }
        return leaseExecutor;
    }

    private void handleEventFailure(WebhookEventRecord event, Exception failure) {
        try {
            boolean deadLettered = queueService.fail(event, failure);
            if (deadLettered) {
                log.error("[Webhook worker] moved failed event to dead-letter eventId={} recordId={} attempts={} "
                                + "approximateRetentionMaxLength={}",
                        event.eventId(), event.recordId(), event.deliveryAttempt(),
                        queueService.deadLetterMaxLength(), failure);
            } else {
                log.warn("[Webhook worker] event remains pending for retry eventId={} recordId={} attempt={} error={}",
                        event.eventId(), event.recordId(), event.deliveryAttempt(), failure.getMessage());
            }
        } catch (Exception queueFailure) {
            failure.addSuppressed(queueFailure);
            log.error("[Webhook worker] failed to record event failure; record remains pending eventId={} recordId={}",
                    event.eventId(), event.recordId(), failure);
            sleep(1_000L);
        }
    }

    private void logInfrastructureFailure(int count, Exception failure) {
        long backoff = infrastructureBackoff(count);
        if (count <= WARN_THRESHOLD || count % 10 == 0) {
            log.error("[Webhook worker] queue infrastructure failure count={} retryInMs={}", count, backoff, failure);
        } else {
            log.warn("[Webhook worker] queue infrastructure failure count={} retryInMs={} error={}",
                    count, backoff, failure.getMessage());
        }
    }

    private static long infrastructureBackoff(int count) {
        int shift = Math.min(Math.max(0, count - 1), 4);
        return Math.min(1_000L * (1L << shift), MAX_INFRASTRUCTURE_BACKOFF_MS);
    }

    private static void sleep(long milliseconds) {
        try {
            Thread.sleep(Math.max(0L, milliseconds));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private record LeaseHeartbeat(ScheduledFuture<?> future, AtomicBoolean owned) {
    }
}
