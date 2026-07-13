package top.hetao.shiyuanticketmp.webhook.sender;

import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import top.hetao.shiyuanticketmp.common.context.TenantContext;
import top.hetao.shiyuanticketmp.webhook.deadletter.WebhookDeadLetterRecord;
import top.hetao.shiyuanticketmp.webhook.deadletter.WebhookDeadLetterService;
import top.hetao.shiyuanticketmp.workorder.event.WorkOrderEvent;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Batches webhook notifications without crossing tenant, channel, or recipient boundaries.
 * Every detached batch is first registered as a stable, recoverable delivery before ownership
 * leaves the in-memory bucket. Network delivery and dead-letter fallback use the same event id
 * and exact serialized body.
 */
@Component
public class WebhookMessageAggregator {

    private static final Logger log = LoggerFactory.getLogger(WebhookMessageAggregator.class);
    private static final String EVENT_TYPE = "BATCH";

    private final int aggregateDelaySeconds;
    private final int maxBatchSize;
    private final int maxWaitSeconds;
    private final int shutdownAwaitSeconds;
    private final int deadLetterAwaitSeconds;
    private final DingTalkDispatcher dingTalkDispatcher;
    private final CargoOwnerDispatcher cargoOwnerDispatcher;
    private final WebhookDeadLetterService deadLetterService;
    private final ThreadPoolExecutor dispatchExecutor;
    private final ThreadPoolExecutor deadLetterExecutor;
    private final ScheduledThreadPoolExecutor scheduler = createScheduler();
    private final ConcurrentHashMap<AggregationKey, Bucket> buffers = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, DeliveryTask> pendingDeliveries = new ConcurrentHashMap<>();
    private final AtomicLong generations = new AtomicLong();
    private final Object lifecycleLock = new Object();
    private final Object completionMonitor = new Object();
    private boolean shuttingDown;

    /** Convenience constructor retained for focused tests whose dispatchers always succeed. */
    public WebhookMessageAggregator(DingTalkDispatcher dingTalkDispatcher,
                                    CargoOwnerDispatcher cargoOwnerDispatcher) {
        this(dingTalkDispatcher, cargoOwnerDispatcher, null,
                10, 100, 60, 2, 100, 30, 1, 100, 30);
    }

    @Autowired
    public WebhookMessageAggregator(DingTalkDispatcher dingTalkDispatcher,
                                    CargoOwnerDispatcher cargoOwnerDispatcher,
                                    WebhookDeadLetterService deadLetterService,
                                    @Value("${webhook.aggregate-delay-seconds:10}") int aggregateDelaySeconds,
                                    @Value("${webhook.aggregate-max-batch-size:100}") int maxBatchSize,
                                    @Value("${webhook.aggregate-max-wait-seconds:60}") int maxWaitSeconds,
                                    @Value("${webhook.aggregate-dispatch-threads:4}") int dispatchThreads,
                                    @Value("${webhook.aggregate-dispatch-queue-capacity:200}") int dispatchQueueCapacity,
                                    @Value("${webhook.aggregate-shutdown-await-seconds:30}") int shutdownAwaitSeconds,
                                    @Value("${webhook.aggregate-deadletter-threads:2}") int deadLetterThreads,
                                    @Value("${webhook.aggregate-deadletter-queue-capacity:1000}") int deadLetterQueueCapacity,
                                    @Value("${webhook.aggregate-deadletter-await-seconds:30}") int deadLetterAwaitSeconds) {
        this.dingTalkDispatcher = Objects.requireNonNull(dingTalkDispatcher);
        this.cargoOwnerDispatcher = Objects.requireNonNull(cargoOwnerDispatcher);
        this.deadLetterService = deadLetterService;
        this.aggregateDelaySeconds = Math.max(1, aggregateDelaySeconds);
        this.maxBatchSize = Math.max(1, maxBatchSize);
        this.maxWaitSeconds = Math.max(1, maxWaitSeconds);
        this.shutdownAwaitSeconds = Math.max(1, shutdownAwaitSeconds);
        this.deadLetterAwaitSeconds = Math.max(1, deadLetterAwaitSeconds);
        this.dispatchExecutor = createExecutor("webhook-aggregate-dispatch-",
                dispatchThreads, dispatchQueueCapacity);
        this.deadLetterExecutor = createExecutor("webhook-aggregate-deadletter-",
                deadLetterThreads, deadLetterQueueCapacity);
    }

    public void submit(WorkOrderEvent event) {
        Objects.requireNonNull(event, "event");
        if (event.getTenantId() == null) {
            throw new IllegalArgumentException("Webhook aggregation requires tenantId");
        }
        ChannelTarget target = Objects.requireNonNull(event.getTargetChannels(), "targetChannels");
        List<DeliveryTask> ready = new ArrayList<>(2);
        synchronized (lifecycleLock) {
            if (shuttingDown) {
                throw new IllegalStateException("Webhook aggregator is shutting down");
            }
            // BOTH enters both channel buckets while holding one lifecycle boundary. Shutdown
            // therefore accepts the event into both channels or rejects it from both.
            if (target == ChannelTarget.DINGTALK || target == ChannelTarget.BOTH) {
                submitToBucketLocked(AggregationKey.dingTalk(event.getTenantId()), event, ready);
            }
            if (target == ChannelTarget.CARGO_OWNER || target == ChannelTarget.BOTH) {
                submitToBucketLocked(AggregationKey.cargoOwner(event), event, ready);
            }
        }
        handoffAll(ready);
    }

    private void submitToBucketLocked(AggregationKey key, WorkOrderEvent event,
                                      List<DeliveryTask> ready) {
        Bucket bucket = buffers.get(key);
        if (bucket == null) {
            bucket = new Bucket(generations.incrementAndGet());
            buffers.put(key, bucket);
            long generation = bucket.generation;
            bucket.maxWait = scheduler.schedule(
                    () -> flush(key, generation), maxWaitSeconds, TimeUnit.SECONDS);
        }
        bucket.events.add(event);
        cancel(bucket.quietPeriod);
        long generation = bucket.generation;
        bucket.quietPeriod = scheduler.schedule(
                () -> flush(key, generation), aggregateDelaySeconds, TimeUnit.SECONDS);
        if (bucket.events.size() >= maxBatchSize) {
            DeliveryTask task = detachLocked(key, generation);
            if (task != null) {
                ready.add(task);
            }
        }
    }

    private void flush(AggregationKey key, long generation) {
        DeliveryTask task;
        synchronized (lifecycleLock) {
            task = detachLocked(key, generation);
        }
        if (task != null) {
            handoff(task);
        }
    }

    /** Flush hook retained for focused tests. */
    private void flush() {
        List<DeliveryTask> tasks;
        synchronized (lifecycleLock) {
            tasks = detachAllLocked();
        }
        handoffAll(tasks);
    }

    /**
     * Transfers ownership atomically from a bucket to the pending-delivery registry. Preparation
     * happens before removal; serialization failure therefore retains the bucket for a later
     * submission or an explicit shutdown failure rather than losing the batch.
     */
    private DeliveryTask detachLocked(AggregationKey key, long generation) {
        Bucket bucket = buffers.get(key);
        if (bucket == null || bucket.generation != generation || bucket.events.isEmpty()) {
            return null;
        }
        DeliveryTask task;
        try {
            List<WorkOrderEvent> batch = List.copyOf(bucket.events);
            byte[] body = prepareBody(key, batch);
            task = new DeliveryTask(bucket.eventId, key, body, batch.size());
        } catch (Exception e) {
            log.error("[Aggregator] cannot prepare batch; retaining buffer tenantId={} channel={} count={}",
                    key.tenantId(), key.channel(), bucket.events.size(), e);
            return null;
        }
        pendingDeliveries.put(task.eventId, task);
        buffers.remove(key, bucket);
        cancel(bucket.quietPeriod);
        cancel(bucket.maxWait);
        return task;
    }

    private List<DeliveryTask> detachAllLocked() {
        List<DeliveryTask> tasks = new ArrayList<>();
        for (AggregationKey key : new ArrayList<>(buffers.keySet())) {
            Bucket bucket = buffers.get(key);
            if (bucket != null) {
                DeliveryTask task = detachLocked(key, bucket.generation);
                if (task != null) {
                    tasks.add(task);
                }
            }
        }
        return tasks;
    }

    private byte[] prepareBody(AggregationKey key, List<WorkOrderEvent> batch) throws Exception {
        return key.channel() == ChannelTarget.DINGTALK
                ? dingTalkDispatcher.prepareBatchBody(batch)
                : cargoOwnerDispatcher.prepareBatchBody(batch);
    }

    private void handoffAll(List<DeliveryTask> tasks) {
        RuntimeException failure = null;
        for (DeliveryTask task : tasks) {
            try {
                handoff(task);
            } catch (RuntimeException e) {
                if (failure == null) {
                    failure = new IllegalStateException("One or more webhook batches could not be handed off");
                }
                failure.addSuppressed(e);
            }
        }
        if (failure != null) {
            throw failure;
        }
    }

    /** Immediate bounded handoff. Saturation falls back to durable storage, never caller execution. */
    private void handoff(DeliveryTask task) {
        handoff(task, true);
    }

    private void handoff(DeliveryTask task, boolean awaitPersistence) {
        if (!task.state.compareAndSet(DeliveryState.NEW, DeliveryState.QUEUED)) {
            return;
        }
        try {
            dispatchExecutor.execute(task);
        } catch (RejectedExecutionException rejected) {
            if (awaitPersistence) {
                persistAndAwait(task, "aggregate dispatch queue rejected the batch", deadLetterAwaitSeconds);
            } else {
                requestPersistence(task, "aggregate dispatch queue rejected during shutdown");
            }
        }
    }

    private void dispatch(DeliveryTask task) {
        DispatchResult result;
        try (TenantContext.Scope ignored = TenantContext.useTenant(task.key.tenantId())) {
            result = task.key.channel() == ChannelTarget.DINGTALK
                    ? dingTalkDispatcher.retryRaw(EVENT_TYPE, task.eventId, task.body)
                    : cargoOwnerDispatcher.retryRaw(EVENT_TYPE, task.eventId, task.body);
        } catch (Exception e) {
            persistAndAwait(task, "aggregate dispatch threw: " + safeMessage(e), deadLetterAwaitSeconds);
            return;
        }
        if (result.success()) {
            if (task.state.compareAndSet(DeliveryState.RUNNING, DeliveryState.SUCCEEDED)) {
                complete(task);
            }
            return;
        }
        persistAndAwait(task, "aggregate dispatch failed: " + result.message(), deadLetterAwaitSeconds);
    }

    private void persistAndAwait(DeliveryTask task, String reason, int timeoutSeconds) {
        CompletableFuture<Void> future = requestPersistence(task, reason);
        if (future == null) {
            return;
        }
        try {
            future.get(timeoutSeconds, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw persistenceFailure(task, "interrupted while waiting for dead-letter persistence", e);
        } catch (TimeoutException e) {
            throw persistenceFailure(task, "dead-letter persistence timed out", e);
        } catch (ExecutionException e) {
            throw persistenceFailure(task, "dead-letter persistence failed", e.getCause());
        }
    }

    private CompletableFuture<Void> requestPersistence(DeliveryTask task, String reason) {
        while (true) {
            DeliveryState state = task.state.get();
            if (state == DeliveryState.DEAD_LETTERED || state == DeliveryState.SUCCEEDED) {
                return null;
            }
            if (state == DeliveryState.PERSISTING) {
                CompletableFuture<Void> existing = task.persistence.get();
                if (existing != null) {
                    return existing;
                }
                Thread.yield();
                continue;
            }
            if (!task.state.compareAndSet(state, DeliveryState.PERSISTING)) {
                continue;
            }
            CompletableFuture<Void> future = new CompletableFuture<>();
            task.persistence.set(future);
            try {
                deadLetterExecutor.execute(() -> persist(task, reason, future));
            } catch (RejectedExecutionException rejected) {
                task.state.set(DeliveryState.PERSIST_FAILED);
                future.completeExceptionally(rejected);
                log.error("[Aggregator] dead-letter executor rejected recoverable batch eventId={} "
                                + "tenantId={} channel={}",
                        task.eventId, task.key.tenantId(), task.key.channel(), rejected);
            }
            return future;
        }
    }

    private void persist(DeliveryTask task, String reason, CompletableFuture<Void> future) {
        try {
            if (deadLetterService == null) {
                throw new IllegalStateException("WebhookDeadLetterService is unavailable");
            }
            WebhookDeadLetterRecord record = WebhookDeadLetterRecord.of(
                    task.eventId, EVENT_TYPE, task.key.channel() + ":aggregate",
                    new String(task.body, StandardCharsets.UTF_8), reason, 1);
            record.setTenantId(task.key.tenantId());
            record.setChannel(channelCode(task.key.channel()));
            record.setConversationId(task.key.conversationId());
            record.setSenderStaffId(task.key.senderStaffId());
            deadLetterService.save(record);
            task.state.set(DeliveryState.DEAD_LETTERED);
            future.complete(null);
            complete(task);
        } catch (Throwable failure) {
            task.state.set(DeliveryState.PERSIST_FAILED);
            future.completeExceptionally(failure);
            log.error("[Aggregator] CRITICAL: batch remains recoverable in memory because dead-letter "
                            + "persistence failed eventId={} tenantId={} channel={}",
                    task.eventId, task.key.tenantId(), task.key.channel(), failure);
            signalCompletion();
        }
    }

    private String channelCode(ChannelTarget channel) {
        return channel == ChannelTarget.DINGTALK
                ? DingTalkDispatcher.CHANNEL_CODE : CargoOwnerDispatcher.CHANNEL_CODE;
    }

    private IllegalStateException persistenceFailure(DeliveryTask task, String message, Throwable cause) {
        log.error("[Aggregator] CRITICAL: {} eventId={} tenantId={} channel={}; batch remains recoverable",
                message, task.eventId, task.key.tenantId(), task.key.channel(), cause);
        return new IllegalStateException(message + " eventId=" + task.eventId, cause);
    }

    private void complete(DeliveryTask task) {
        pendingDeliveries.remove(task.eventId, task);
        signalCompletion();
    }

    private void signalCompletion() {
        synchronized (completionMonitor) {
            completionMonitor.notifyAll();
        }
    }

    @PreDestroy
    public void shutdown() {
        List<DeliveryTask> newlyDetached;
        synchronized (lifecycleLock) {
            if (!shuttingDown) {
                shuttingDown = true;
                newlyDetached = detachAllLocked();
                scheduler.shutdown();
            } else {
                newlyDetached = List.of();
            }
        }

        RuntimeException failure = null;
        for (DeliveryTask task : newlyDetached) {
            handoff(task, false);
        }

        dispatchExecutor.shutdown();
        long deliveryDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(shutdownAwaitSeconds);
        awaitExecutor(dispatchExecutor, deliveryDeadline);
        if (!dispatchExecutor.isTerminated()) {
            dispatchExecutor.shutdownNow();
        }

        // Any queued, active, rejected, or previously failed delivery is now atomically claimed
        // for fallback. A task whose normal result won the state race is already absent.
        for (DeliveryTask task : List.copyOf(pendingDeliveries.values())) {
            requestPersistence(task, "application shutdown before aggregate delivery completed");
        }

        long persistenceDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(deadLetterAwaitSeconds);
        awaitPending(persistenceDeadline);
        awaitExecutor(scheduler, persistenceDeadline);

        if (pendingDeliveries.isEmpty() && buffers.isEmpty()) {
            deadLetterExecutor.shutdown();
            awaitExecutor(deadLetterExecutor, persistenceDeadline);
        } else {
            IllegalStateException pendingFailure = new IllegalStateException(
                    "Webhook aggregator shutdown left " + pendingDeliveries.size()
                            + " pending deliveries and " + buffers.size()
                            + " buffered batches not durably persisted");
            log.error("[Aggregator] CRITICAL: shutdown boundary reached with pending eventIds={} "
                            + "bufferedKeys={}",
                    pendingDeliveries.keySet(), buffers.keySet(), pendingFailure);
            if (failure == null) {
                failure = pendingFailure;
            } else {
                failure.addSuppressed(pendingFailure);
            }
        }
        if (failure != null) {
            throw failure;
        }
    }

    private void awaitPending(long deadline) {
        synchronized (completionMonitor) {
            while (!pendingDeliveries.isEmpty()) {
                long remaining = deadline - System.nanoTime();
                if (remaining <= 0) {
                    return;
                }
                try {
                    TimeUnit.NANOSECONDS.timedWait(completionMonitor, remaining);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        }
    }

    private void awaitExecutor(java.util.concurrent.ExecutorService executor, long deadline) {
        long remaining = deadline - System.nanoTime();
        if (remaining <= 0) {
            return;
        }
        try {
            executor.awaitTermination(remaining, TimeUnit.NANOSECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static ScheduledThreadPoolExecutor createScheduler() {
        ScheduledThreadPoolExecutor executor = new ScheduledThreadPoolExecutor(1, runnable -> {
            Thread thread = new Thread(runnable, "webhook-aggregator");
            thread.setDaemon(true);
            return thread;
        });
        executor.setRemoveOnCancelPolicy(true);
        executor.setExecuteExistingDelayedTasksAfterShutdownPolicy(false);
        return executor;
    }

    private static ThreadPoolExecutor createExecutor(String threadPrefix, int threads, int capacity) {
        int boundedThreads = Math.max(1, threads);
        int boundedCapacity = Math.max(1, capacity);
        AtomicLong sequence = new AtomicLong();
        return new ThreadPoolExecutor(
                boundedThreads, boundedThreads, 0L, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(boundedCapacity),
                runnable -> {
                    Thread thread = new Thread(runnable, threadPrefix + sequence.incrementAndGet());
                    thread.setDaemon(true);
                    return thread;
                },
                new ThreadPoolExecutor.AbortPolicy());
    }

    private static void cancel(ScheduledFuture<?> future) {
        if (future != null) {
            future.cancel(false);
        }
    }

    private static String safeMessage(Throwable throwable) {
        String message = throwable.getMessage();
        return message == null ? throwable.getClass().getSimpleName() : message;
    }

    private record AggregationKey(Long tenantId, ChannelTarget channel,
                                  String conversationId, String senderStaffId) {
        static AggregationKey dingTalk(Long tenantId) {
            return new AggregationKey(tenantId, ChannelTarget.DINGTALK, null, null);
        }

        static AggregationKey cargoOwner(WorkOrderEvent event) {
            return new AggregationKey(event.getTenantId(), ChannelTarget.CARGO_OWNER,
                    normalize(event.getConversationId()), normalize(event.getSenderStaffId()));
        }

        private static String normalize(String value) {
            return value == null || value.isBlank() ? null : value.trim();
        }
    }

    private final class DeliveryTask implements Runnable {
        private final String eventId;
        private final AggregationKey key;
        private final byte[] body;
        private final int batchSize;
        private final AtomicReference<DeliveryState> state = new AtomicReference<>(DeliveryState.NEW);
        private final AtomicReference<CompletableFuture<Void>> persistence = new AtomicReference<>();

        private DeliveryTask(String eventId, AggregationKey key, byte[] body, int batchSize) {
            this.eventId = eventId;
            this.key = key;
            this.body = body.clone();
            this.batchSize = batchSize;
        }

        @Override
        public void run() {
            if (!state.compareAndSet(DeliveryState.QUEUED, DeliveryState.RUNNING)) {
                return;
            }
            log.debug("[Aggregator] dispatching stable batch eventId={} tenantId={} channel={} count={}",
                    eventId, key.tenantId(), key.channel(), batchSize);
            dispatch(this);
        }
    }

    private enum DeliveryState {
        NEW, QUEUED, RUNNING, PERSISTING, PERSIST_FAILED, SUCCEEDED, DEAD_LETTERED
    }

    private static final class Bucket {
        private final long generation;
        private final String eventId = UUID.randomUUID().toString();
        private final List<WorkOrderEvent> events = new ArrayList<>();
        private ScheduledFuture<?> quietPeriod;
        private ScheduledFuture<?> maxWait;

        private Bucket(long generation) {
            this.generation = generation;
        }
    }
}
