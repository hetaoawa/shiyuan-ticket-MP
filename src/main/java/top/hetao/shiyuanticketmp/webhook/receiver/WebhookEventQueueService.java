package top.hetao.shiyuanticketmp.webhook.receiver;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataAccessException;
import org.springframework.data.domain.Range;
import org.springframework.data.redis.connection.RedisStreamCommands;
import org.springframework.data.redis.connection.stream.ByteRecord;
import org.springframework.data.redis.connection.stream.Consumer;
import org.springframework.data.redis.connection.stream.PendingMessage;
import org.springframework.data.redis.connection.stream.PendingMessages;
import org.springframework.data.redis.connection.stream.ReadOffset;
import org.springframework.data.redis.connection.stream.StreamOffset;
import org.springframework.data.redis.connection.stream.StreamReadOptions;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.HashOperations;
import org.springframework.data.redis.core.StreamOperations;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/** Reliable webhook queue backed by a Redis Stream consumer group. */
@Service
public class WebhookEventQueueService {

    private static final Logger log = LoggerFactory.getLogger(WebhookEventQueueService.class);

    // The hash tag keeps the source and dead-letter Streams in one Redis Cluster slot.
    static final String STREAM_KEY = "webhook:{events}:stream";
    static final String DEAD_LETTER_STREAM_KEY = "webhook:{events}:dead-letter";
    private static final String FAILURE_AT_KEY = "webhook:{events}:failure-at";
    static final String CONSUMER_GROUP = "webhook-event-workers";
    private static final String LEGACY_QUEUE_KEY = "webhook:events:queue";
    private static final String LEGACY_PROCESSING_KEY = "webhook:events:processing";
    private static final String DEDUPE_KEY_PREFIX = "webhook:{events}:dedupe:";
    private static final long MINIMUM_DEDUPE_TTL_SECONDS = 300L;

    private static final DefaultRedisScript<Long> ACK_AND_DELETE_SCRIPT = longScript("""
            local pending = redis.call('XPENDING', KEYS[1], ARGV[1], ARGV[2], ARGV[2], 1)
            if #pending == 0 or pending[1][2] ~= ARGV[3] then return 0 end
            local acked = redis.call('XACK', KEYS[1], ARGV[1], ARGV[2])
            if acked == 1 then
                redis.call('XDEL', KEYS[1], ARGV[2])
                redis.call('HDEL', KEYS[2], ARGV[2])
            end
            return acked
            """);

    private static final DefaultRedisScript<Long> RENEW_LEASE_SCRIPT = longScript("""
            local pending = redis.call('XPENDING', KEYS[1], ARGV[1], ARGV[2], ARGV[2], 1)
            if #pending == 0 or pending[1][2] ~= ARGV[3] then return 0 end
            local renewed = redis.call('XCLAIM', KEYS[1], ARGV[1], ARGV[3], 0, ARGV[2], 'JUSTID')
            return #renewed
            """);

    private static final DefaultRedisScript<Long> MARK_FAILURE_SCRIPT = longScript("""
            local pending = redis.call('XPENDING', KEYS[1], ARGV[1], ARGV[2], ARGV[2], 1)
            if #pending == 0 or pending[1][2] ~= ARGV[4] then return 0 end
            redis.call('HSET', KEYS[2], ARGV[2], ARGV[3])
            return 1
            """);

    private static final DefaultRedisScript<Long> DEAD_LETTER_SCRIPT = longScript("""
            local pending = redis.call('XPENDING', KEYS[1], ARGV[1], ARGV[2], ARGV[2], 1)
            if #pending == 0 or pending[1][2] ~= ARGV[10] then return 0 end
            redis.call('XADD', KEYS[2], 'MAXLEN', '~', ARGV[11], '*',
                'sourceRecordId', ARGV[2], 'eventId', ARGV[3], 'eventType', ARGV[4],
                'timestamp', ARGV[5], 'payload', ARGV[6], 'attempts', ARGV[7],
                'error', ARGV[8], 'failedAt', ARGV[9], 'consumer', ARGV[10])
            local acked = redis.call('XACK', KEYS[1], ARGV[1], ARGV[2])
            if acked == 1 then
                redis.call('XDEL', KEYS[1], ARGV[2])
                redis.call('HDEL', KEYS[3], ARGV[2])
            end
            return acked
            """);

    private static final DefaultRedisScript<Long> MIGRATE_LEGACY_QUEUE_SCRIPT = longScript("""
            local moved = 0
            for i = 1, tonumber(ARGV[1]) do
                local payload = redis.call('LPOP', KEYS[1])
                if not payload then break end
                redis.call('XADD', KEYS[2], '*', 'eventId', 'legacy-' .. redis.sha1hex(payload),
                    'eventType', '', 'timestamp', '', 'payload', payload)
                moved = moved + 1
            end
            return moved
            """);

    private static final DefaultRedisScript<Long> MIGRATE_LEGACY_PROCESSING_SCRIPT = longScript("""
            local moved = 0
            for i = 1, tonumber(ARGV[1]) do
                local payload = redis.call('SPOP', KEYS[1])
                if not payload then break end
                redis.call('XADD', KEYS[2], '*', 'eventId', 'legacy-' .. redis.sha1hex(payload),
                    'eventType', '', 'timestamp', '', 'payload', payload)
                moved = moved + 1
            end
            return moved
            """);

    private static final DefaultRedisScript<Long> RELEASE_RESERVATION_SCRIPT = longScript("""
            if redis.call('GET', KEYS[1]) == ARGV[1] then
                return redis.call('DEL', KEYS[1])
            end
            return 0
            """);

    private final StringRedisTemplate redisTemplate;
    private final String consumerName;
    private final AtomicBoolean groupReady = new AtomicBoolean(false);
    private final AtomicBoolean legacyBacklogLogged = new AtomicBoolean(false);
    private final AtomicReference<String> pendingScanCursor = new AtomicReference<>();
    private final AtomicReference<String> pendingScanUpperBound = new AtomicReference<>();

    @Value("${webhook.worker.read-block-ms:5000}")
    private long readBlockMs = 5_000L;

    @Value("${webhook.worker.lease-timeout-ms:30000}")
    private long leaseTimeoutMs = 30_000L;

    @Value("${webhook.worker.retry-base-delay-ms:1000}")
    private long retryBaseDelayMs = 1_000L;

    @Value("${webhook.worker.retry-max-delay-ms:30000}")
    private long retryMaxDelayMs = 30_000L;

    @Value("${webhook.worker.max-attempts:5}")
    private int maxAttempts = 5;

    @Value("${webhook.worker.pending-scan-size:100}")
    private long pendingScanSize = 100L;

    @Value("${webhook.worker.legacy-migration-batch-size:100}")
    private long legacyMigrationBatchSize = 100L;

    @Value("${webhook.worker.migrate-legacy-queue:false}")
    private boolean migrateLegacyQueue;

    @Value("${webhook.worker.migrate-legacy-processing:false}")
    private boolean migrateLegacyProcessing;

    @Value("${webhook.worker.dead-letter-max-length:10000}")
    private long configuredDeadLetterMaxLength = 10_000L;

    @Value("${webhook.receiver.dedupe-ttl-seconds:600}")
    private long configuredDedupeTtlSeconds = 600L;

    public WebhookEventQueueService(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
        this.consumerName = buildConsumerName();
    }

    /** Atomically reserves a signed event id. Null means another request already owns it. */
    public String reserveEvent(String eventId) {
        String token = UUID.randomUUID().toString();
        Duration ttl = Duration.ofSeconds(Math.max(
                MINIMUM_DEDUPE_TTL_SECONDS, configuredDedupeTtlSeconds));
        Boolean acquired = redisTemplate.opsForValue()
                .setIfAbsent(dedupeKey(eventId), token, ttl);
        return Boolean.TRUE.equals(acquired) ? token : null;
    }

    /** Releases only the reservation created by the supplied owner token. */
    public void releaseEventReservation(String eventId, String token) {
        if (eventId == null || token == null) {
            return;
        }
        redisTemplate.execute(RELEASE_RESERVATION_SCRIPT,
                List.of(dedupeKey(eventId)), token);
    }

    /** Adds the original body and its stable sender metadata to the Stream. */
    public void push(String eventId, String eventType, String timestamp, String eventJson) {
        Map<String, String> envelope = new LinkedHashMap<>();
        envelope.put("eventId", valueOrEmpty(eventId));
        envelope.put("eventType", valueOrEmpty(eventType));
        envelope.put("timestamp", valueOrEmpty(timestamp));
        envelope.put("payload", eventJson);
        streams().add(STREAM_KEY, envelope);
        log.debug("[Webhook receiver] queued eventId={}", eventId);
    }

    /** Compatibility entry point for internal callers without transport metadata. */
    public void push(String eventJson) {
        push("internal-" + UUID.randomUUID(), "", Long.toString(Instant.now().getEpochSecond()), eventJson);
    }

    /** Claims an expired pending record first, otherwise blocks for a new record. */
    public WebhookEventRecord claimNext() {
        ensureConsumerGroup();
        try {
            migrateLegacyBatch();
            WebhookEventRecord reclaimed = reclaimDuePending();
            return reclaimed != null ? reclaimed : readNew();
        } catch (DataAccessException e) {
            if (!hasRedisError(e, "NOGROUP")) {
                throw e;
            }
            groupReady.set(false);
            resetPendingScanRound();
            ensureConsumerGroup();
            return readNew();
        }
    }

    /** ACK and removal are one Redis script so acknowledged entries cannot leak in the Stream. */
    public boolean ack(WebhookEventRecord event) {
        Long result = redisTemplate.execute(ACK_AND_DELETE_SCRIPT, List.of(STREAM_KEY, FAILURE_AT_KEY),
                CONSUMER_GROUP, event.recordId(), consumerName);
        return result != null && result == 1L;
    }

    /** Renews only a record still owned by this consumer; JUSTID keeps the delivery count unchanged. */
    public boolean renewLease(WebhookEventRecord event) {
        Long result = redisTemplate.execute(RENEW_LEASE_SCRIPT, List.of(STREAM_KEY),
                CONSUMER_GROUP, event.recordId(), consumerName);
        return result != null && result == 1L;
    }

    /**
     * Leaves retryable failures in the PEL. The PEL idle time drives backoff and reclaim.
     * The final move to dead-letter, ACK and source deletion are atomic.
     */
    public boolean fail(WebhookEventRecord event, Throwable failure) {
        if (!isAttemptExhausted(event)) {
            redisTemplate.execute(MARK_FAILURE_SCRIPT, List.of(STREAM_KEY, FAILURE_AT_KEY),
                    CONSUMER_GROUP, event.recordId(), Long.toString(System.currentTimeMillis()), consumerName);
            return false;
        }
        String message = failure == null ? "maximum delivery attempts exceeded" : valueOrEmpty(failure.getMessage());
        Long result = redisTemplate.execute(DEAD_LETTER_SCRIPT,
                List.of(STREAM_KEY, DEAD_LETTER_STREAM_KEY, FAILURE_AT_KEY),
                CONSUMER_GROUP, event.recordId(), valueOrEmpty(event.eventId()), valueOrEmpty(event.eventType()),
                valueOrEmpty(event.timestamp()), event.payload(), Long.toString(event.deliveryAttempt()),
                message, Instant.now().toString(), consumerName, Long.toString(deadLetterMaxLength()));
        return result != null && result == 1L;
    }

    boolean isAttemptExhausted(WebhookEventRecord event) {
        return event.deliveryAttempt() >= Math.max(1, maxAttempts);
    }

    boolean hasExceededAttemptLimit(WebhookEventRecord event) {
        return event.deliveryAttempt() > Math.max(1, maxAttempts);
    }

    long retryDelayMillis(long completedDeliveries) {
        long base = Math.max(1L, retryBaseDelayMs);
        long maximum = Math.max(base, retryMaxDelayMs);
        int shift = (int) Math.min(Math.max(0L, completedDeliveries - 1L), 30L);
        long multiplier = 1L << shift;
        return multiplier > maximum / base ? maximum : Math.min(base * multiplier, maximum);
    }

    boolean isRetryDue(long idleMillis, long deliveryCount, Long failureAtMillis, long nowMillis) {
        if (idleMillis < Math.max(1L, leaseTimeoutMs)) {
            return false;
        }
        return failureAtMillis == null
                || nowMillis >= failureAtMillis + retryDelayMillis(deliveryCount);
    }

    boolean isLegacyQueueMigrationEnabled() {
        return migrateLegacyQueue;
    }

    boolean isLegacyProcessingMigrationEnabled() {
        return migrateLegacyProcessing;
    }

    long deadLetterMaxLength() {
        return Math.max(1L, configuredDeadLetterMaxLength);
    }

    /** Number of records not delivered to the group, including a rolling-upgrade legacy backlog. */
    public long queueSize() {
        long streamLength = valueOrZero(streams().size(STREAM_KEY));
        long pending = streamPendingSize();
        long legacyQueued = valueOrZero(redisTemplate.opsForList().size(LEGACY_QUEUE_KEY));
        return Math.max(0L, streamLength - pending) + legacyQueued;
    }

    /** Number of claimed records awaiting ACK, including records from the former processing Set. */
    public long processingSize() {
        return streamPendingSize() + valueOrZero(redisTemplate.opsForSet().size(LEGACY_PROCESSING_KEY));
    }

    public long deadLetterSize() {
        return valueOrZero(streams().size(DEAD_LETTER_STREAM_KEY));
    }

    String consumerName() {
        return consumerName;
    }

    private WebhookEventRecord reclaimDuePending() {
        String startingCursor = pendingScanCursor.get();
        PendingScanPage page = loadPendingPage(startingCursor);
        WebhookEventRecord reclaimed = scanPendingPage(page);
        if (reclaimed != null) {
            return reclaimed;
        }
        updatePendingCursor(page);
        if (page.endReached() && startingCursor != null) {
            pendingScanCursor.set(null);
            PendingScanPage wrapped = loadPendingPage(null);
            reclaimed = scanPendingPage(wrapped);
            if (reclaimed != null) {
                return reclaimed;
            }
            updatePendingCursor(wrapped);
        }
        return null;
    }

    private WebhookEventRecord scanPendingPage(PendingScanPage page) {
        long now = System.currentTimeMillis();
        for (PendingMessage message : page.messages()) {
            pendingScanCursor.set(message.getIdAsString());
            Long failureAt = parseLong(failures().get(FAILURE_AT_KEY, message.getIdAsString()));
            if (!isRetryDue(message.getElapsedTimeSinceLastDelivery().toMillis(),
                    message.getTotalDeliveryCount(), failureAt, now)) {
                continue;
            }
            List<ByteRecord> claimed = redisTemplate.execute((RedisCallback<List<ByteRecord>>) connection -> connection.streamCommands().xClaim(
                    bytes(STREAM_KEY), CONSUMER_GROUP, consumerName,
                    RedisStreamCommands.XClaimOptions.minIdle(Duration.ofMillis(Math.max(1L, leaseTimeoutMs)))
                            .ids(message.getId())));
            if (claimed != null && !claimed.isEmpty()) {
                failures().delete(FAILURE_AT_KEY, message.getIdAsString());
                return toEvent(claimed.get(0), message.getTotalDeliveryCount() + 1L);
            }
        }
        return null;
    }

    private PendingScanPage loadPendingPage(String cursor) {
        long batchSize = Math.max(1L, pendingScanSize);
        String upperBound = pendingScanUpperBound.get();
        if (upperBound == null) {
            var summary = streams().pending(STREAM_KEY, CONSUMER_GROUP);
            if (summary.getTotalPendingMessages() == 0L) {
                resetPendingScanRound();
                return new PendingScanPage(List.of(), cursor, true);
            }
            upperBound = summary.maxMessageId();
            pendingScanUpperBound.set(upperBound);
        }
        long requestCount = cursor == null ? batchSize : batchSize + 1L;
        Range<?> range = cursor == null
                ? Range.leftUnbounded(Range.Bound.inclusive(upperBound))
                : Range.closed(cursor, upperBound);
        PendingMessages raw = streams().pending(STREAM_KEY, CONSUMER_GROUP, range, requestCount);
        return normalizePendingPage(raw, cursor, batchSize, upperBound);
    }

    PendingScanPage normalizePendingPage(PendingMessages raw, String cursor, long batchSize, String upperBound) {
        long limit = Math.max(1L, batchSize);
        long requested = cursor == null ? limit : limit + 1L;
        List<PendingMessage> messages = new ArrayList<>((int) Math.min(limit, 10_000L));
        for (PendingMessage message : raw) {
            if (cursor != null && cursor.equals(message.getIdAsString())) {
                continue;
            }
            if (messages.size() >= limit) {
                break;
            }
            messages.add(message);
        }
        String nextCursor = messages.isEmpty() ? cursor : messages.get(messages.size() - 1).getIdAsString();
        boolean reachedSnapshotEnd = upperBound != null && upperBound.equals(nextCursor);
        return new PendingScanPage(List.copyOf(messages), nextCursor,
                reachedSnapshotEnd || raw.size() < requested);
    }

    private void updatePendingCursor(PendingScanPage page) {
        if (page.endReached()) {
            resetPendingScanRound();
        } else {
            pendingScanCursor.set(page.nextCursor());
        }
    }

    private void resetPendingScanRound() {
        pendingScanCursor.set(null);
        pendingScanUpperBound.set(null);
    }

    private WebhookEventRecord readNew() {
        List<ByteRecord> records = redisTemplate.execute((RedisCallback<List<ByteRecord>>) connection -> connection.streamCommands().xReadGroup(
                Consumer.from(CONSUMER_GROUP, consumerName),
                StreamReadOptions.empty().count(1).block(Duration.ofMillis(Math.max(1L, readBlockMs))),
                StreamOffset.create(bytes(STREAM_KEY), ReadOffset.lastConsumed())));
        return records == null || records.isEmpty() ? null : toEvent(records.get(0), 1L);
    }

    private WebhookEventRecord toEvent(ByteRecord record, long attempt) {
        Map<String, String> fields = new LinkedHashMap<>();
        record.getValue().forEach((key, value) -> fields.put(string(key), string(value)));
        return new WebhookEventRecord(record.getId().getValue(), fields.getOrDefault("eventId", ""),
                fields.getOrDefault("eventType", ""), fields.getOrDefault("timestamp", ""),
                fields.getOrDefault("payload", ""), attempt);
    }

    private void ensureConsumerGroup() {
        if (groupReady.get()) {
            return;
        }
        synchronized (groupReady) {
            if (groupReady.get()) {
                return;
            }
            try {
                redisTemplate.execute((RedisCallback<String>) connection -> connection.streamCommands().xGroupCreate(
                        bytes(STREAM_KEY), CONSUMER_GROUP, ReadOffset.from("0-0"), true));
            } catch (DataAccessException e) {
                if (!hasRedisError(e, "BUSYGROUP")) {
                    throw e;
                }
            }
            groupReady.set(true);
            log.info("[Webhook worker] consumer group ready group={} consumer={}", CONSUMER_GROUP, consumerName);
        }
    }

    private void migrateLegacyBatch() {
        if (!migrateLegacyQueue && !migrateLegacyProcessing) {
            logLegacyBacklogOnce();
            return;
        }
        long batchSize = Math.max(1L, legacyMigrationBatchSize);
        if (migrateLegacyQueue) {
            redisTemplate.execute(MIGRATE_LEGACY_QUEUE_SCRIPT,
                    List.of(LEGACY_QUEUE_KEY, STREAM_KEY),
                    Long.toString(batchSize));
        }
        if (migrateLegacyProcessing) {
            redisTemplate.execute(MIGRATE_LEGACY_PROCESSING_SCRIPT,
                    List.of(LEGACY_PROCESSING_KEY, STREAM_KEY),
                    Long.toString(batchSize));
        }
    }

    private void logLegacyBacklogOnce() {
        if (!legacyBacklogLogged.compareAndSet(false, true)) {
            return;
        }
        log.info("[Webhook worker] legacy queue and processing migration are disabled by default; legacy backlog "
                + "remains visible in queue metrics and must be drained before deployment or migrated during "
                + "an explicit standalone maintenance window");
    }

    private long streamPendingSize() {
        ensureConsumerGroup();
        try {
            return streams().pending(STREAM_KEY, CONSUMER_GROUP).getTotalPendingMessages();
        } catch (DataAccessException e) {
            if (hasRedisError(e, "NOGROUP")) {
                groupReady.set(false);
                resetPendingScanRound();
                return 0L;
            }
            throw e;
        }
    }

    private static DefaultRedisScript<Long> longScript(String text) {
        DefaultRedisScript<Long> script = new DefaultRedisScript<>();
        script.setScriptText(text);
        script.setResultType(Long.class);
        return script;
    }

    private static String dedupeKey(String eventId) {
        return DEDUPE_KEY_PREFIX + eventId;
    }

    private StreamOperations<String, String, String> streams() {
        return redisTemplate.opsForStream();
    }

    private HashOperations<String, String, String> failures() {
        return redisTemplate.opsForHash();
    }

    private static boolean hasRedisError(Throwable failure, String marker) {
        Throwable current = failure;
        while (current != null) {
            if (current.getMessage() != null && current.getMessage().contains(marker)) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    private static String buildConsumerName() {
        String host = System.getenv("HOSTNAME");
        if (host == null || host.isBlank()) {
            host = System.getenv("COMPUTERNAME");
        }
        if (host == null || host.isBlank()) {
            host = "unknown-host";
        }
        return host + "-" + ProcessHandle.current().pid() + "-" + UUID.randomUUID().toString().substring(0, 8);
    }

    private static long valueOrZero(Long value) {
        return value == null ? 0L : value;
    }

    private static Long parseLong(String value) {
        if (value == null) {
            return null;
        }
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private static String valueOrEmpty(String value) {
        return value == null ? "" : value;
    }

    private static byte[] bytes(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }

    private static String string(byte[] value) {
        return new String(value, StandardCharsets.UTF_8);
    }

    record PendingScanPage(List<PendingMessage> messages, String nextCursor, boolean endReached) {
    }
}
