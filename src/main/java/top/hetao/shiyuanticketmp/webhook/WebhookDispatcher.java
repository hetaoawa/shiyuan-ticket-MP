package top.hetao.shiyuanticketmp.webhook;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import top.hetao.shiyuanticketmp.util.HMACUtils;
import top.hetao.shiyuanticketmp.webhook.deadletter.WebhookDeadLetterRecord;
import top.hetao.shiyuanticketmp.webhook.deadletter.WebhookDeadLetterService;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.UUID;

/**
 * WebHook 异步调度器
 *
 * <p><b>event_id 统一生成策略：</b>
 * {@code eventId} 在 {@link #dispatch} 入口处由本类唯一生成，
 * 随后同步注入两处，保证严格一致：
 * <ol>
 *   <li>HTTP 请求头 {@code X-Event-Id} —— 接收方可从 Header 读取，无需解析 Body</li>
 *   <li>{@link WebhookEnvelope#getEventId()} —— 序列化进 Body JSON data 的外层字段</li>
 * </ol>
 *
 * <p><b>两种调用入口：</b>
 * <ul>
 *   <li>{@link #dispatch}    — 业务对象入口，本类负责序列化，适合首次投递</li>
 *   <li>{@link #dispatchRaw} — 原始字节入口，跳过序列化，专供死信补偿重投使用</li>
 * </ul>
 *
 * <p><b>指数退避公式：</b>{@code baseDelayMs × 2^(attempt-1) + random(0, baseDelayMs)}，
 * 上限钳位 30 秒，防止下游长时间不可用时线程被永久占用。
 *
 * <p><b>失败兜底：</b>全量重试耗尽后调用 {@link WebhookDeadLetterService#save}
 * 以 {@code REQUIRES_NEW} 独立事务落库死信，由管理员后台页面手动补偿，保证事件不丢失。
 */
@Component
public class WebhookDispatcher {

    private static final Logger log = LoggerFactory.getLogger(WebhookDispatcher.class);

    /** 请求头：HMAC-SHA256 签名 */
    private static final String HEADER_SIGNATURE  = "X-Signature";
    /** 请求头：发送时间戳（Unix 秒），接收方可据此实现防重放（5 分钟窗口） */
    private static final String HEADER_TIMESTAMP  = "X-Timestamp";
    /** 请求头：唯一事件 ID，与 Body 中 WebhookEnvelope.eventId 完全一致 */
    private static final String HEADER_EVENT_ID   = "X-Event-Id";
    /** 请求头：事件类型，接收方据此路由分发 */
    private static final String HEADER_EVENT_TYPE = "X-Event-Type";

    @Value("${webhook.secret}")
    private String sharedSecret;

    /** 最大重试次数（不含首次），默认 5 次，共最多 6 次尝试 */
    @Value("${webhook.max-retry:5}")
    private int maxRetry;

    /** 首次重试基础延迟（毫秒），后续每轮翻倍 */
    @Value("${webhook.base-delay-ms:500}")
    private long baseDelayMs;

    /** 单次请求超时（秒） */
    @Value("${webhook.timeout-seconds:10}")
    private int timeoutSeconds;

    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();

    private final ObjectMapper            objectMapper;
    private final WebhookDeadLetterService deadLetterService;

    public WebhookDispatcher(ObjectMapper objectMapper,
                             WebhookDeadLetterService deadLetterService) {
        this.objectMapper      = objectMapper;
        this.deadLetterService = deadLetterService;
    }

    // ----------------------------------------------------------------
    // 公共入口 1：业务对象入口（首次投递）
    // ----------------------------------------------------------------

    /**
     * 异步派发 WebHook 事件（首次投递）。
     *
     * <p>标注 {@code @Async("webhookExecutor")}，调用方立即返回，
     * 实际 HTTP 请求在独立线程池中执行，不阻塞主业务流程。
     *
     * <p><b>event_id 生成时机：</b>在本方法入口处统一生成，
     * 同时写入 Header 和 {@link WebhookEnvelope}，两处值恒相等。
     *
     * @param targetUrl 接收方端点 URL
     * @param eventType 事件类型，如 {@code "WORK_ORDER.CLOSED"}
     * @param payload   业务数据对象，将被包裹进 {@link WebhookEnvelope} 后序列化为 JSON
     */
    @Async("webhookExecutor")
    public void dispatch(String targetUrl, String eventType, Object payload) {
        // ① 入口唯一生成 eventId，全程传递不再重新生成
        String eventId = UUID.randomUUID().toString();
        log.info("[WebHook] 开始投递 eventId={} type={} url={}", eventId, eventType, targetUrl);

        try {
            // ② 用信封包裹 payload，eventId 同时注入 Header（由 sendRequest 写入）和 Body
            WebhookEnvelope envelope = WebhookEnvelope.wrap(eventId, eventType, payload);
            byte[] body = objectMapper.writeValueAsBytes(envelope);
            doDispatchWithRetry(targetUrl, eventType, eventId, body);
        } catch (Exception e) {
            // 序列化失败属于编程错误，不写死信（重投也会失败）
            log.error("[WebHook] payload 序列化失败，eventId={}", eventId, e);
        }
    }

    // ----------------------------------------------------------------
    // 公共入口 2：原始字节入口（死信补偿重投）
    // ----------------------------------------------------------------

    /**
     * 使用原始 JSON 字节重新投递，专供 {@link WebhookDeadLetterService#retry} 调用。
     *
     * <p><b>为何保持原 eventId 不变：</b>
     * 若上次投递实际已送达（网络超时导致本端误判失败），
     * 接收方将因 {@code eventId} 重复而幂等忽略，不会产生重复处理。
     *
     * @param targetUrl 原始目标 URL（从死信记录中读取）
     * @param eventType 原始事件类型
     * @param eventId   原始事件 ID（保持不变）
     * @param rawBody   原始请求体字节（死信记录中存储的 payload 字段内容）
     */
    @Async("webhookExecutor")
    public void dispatchRaw(String targetUrl, String eventType,
                            String eventId, byte[] rawBody) {
        log.info("[WebHook][补偿] 开始重投 eventId={} type={}", eventId, eventType);
        doDispatchWithRetry(targetUrl, eventType, eventId, rawBody);
    }

    // ----------------------------------------------------------------
    // 核心：指数退避重试
    // ----------------------------------------------------------------

    /**
     * 带指数退避重试的 HTTP 投递核心，被两个公共入口共享。
     *
     * <p><b>重试判断规则：</b>
     * <ul>
     *   <li>2xx → 投递成功，立即返回，不写死信</li>
     *   <li>4xx → 请求本身有问题，重试无意义，直接落死信</li>
     *   <li>5xx → 下游临时故障，退避后重试</li>
     *   <li>网络异常 → 退避后重试</li>
     *   <li>达到 maxRetry 上限 → 落死信</li>
     * </ul>
     *
     * @param targetUrl 目标 URL
     * @param eventType 事件类型（用于死信记录）
     * @param eventId   事件唯一 ID（全程不变）
     * @param body      已序列化的 JSON 请求体字节数组
     */
    private void doDispatchWithRetry(String targetUrl, String eventType,
                                     String eventId, byte[] body) {
        int    attempt   = 0;
        String lastError = "未知错误";

        while (attempt <= maxRetry) {
            attempt++;
            try {
                HttpResponse<String> response = sendRequest(targetUrl, eventType, eventId, body);

                if (isSuccess(response.statusCode())) {
                    log.info("[WebHook] 投递成功 eventId={} attempt={} status={}",
                            eventId, attempt, response.statusCode());
                    return; // ✅ 成功退出
                }

                lastError = "HTTP " + response.statusCode() + ": " + truncate(response.body());

                if (isClientError(response.statusCode())) {
                    // 4xx：请求本身有误，重试无意义，直接落死信
                    log.error("[WebHook] 4xx 不可重试 eventId={} status={}",
                            eventId, response.statusCode());
                    break;
                }

                log.warn("[WebHook] 5xx 错误 eventId={} attempt={}/{} status={}",
                        eventId, attempt, maxRetry + 1, response.statusCode());

            } catch (Exception e) {
                lastError = e.getClass().getSimpleName() + ": " + e.getMessage();
                log.warn("[WebHook] 网络异常 eventId={} attempt={}/{} error={}",
                        eventId, attempt, maxRetry + 1, e.getMessage());
            }

            if (attempt > maxRetry) {
                log.error("[WebHook] 已达最大重试次数，eventId={}", eventId);
                break;
            }

            long backoff = calcBackoff(attempt);
            log.info("[WebHook] 退避等待 {}ms eventId={}", backoff, eventId);
            sleep(backoff);
        }

        // ❌ 全量失败 → 落库死信
        persistDeadLetter(eventId, eventType, targetUrl, body, lastError, attempt);
    }

    // ----------------------------------------------------------------
    // HTTP 请求构造
    // ----------------------------------------------------------------

    /**
     * 构造并发送单次 HTTP POST 请求。
     *
     * <p>签名覆盖完整 body 字节数组（包含信封外层字段），
     * 接收方用相同 body 重新计算 HMAC-SHA256 后与 {@code X-Signature} 对比即可验证来源。
     */
    private HttpResponse<String> sendRequest(String targetUrl, String eventType,
                                             String eventId, byte[] body) throws Exception {
        String timestamp = String.valueOf(System.currentTimeMillis() / 1000L);
        // 签名基于完整 body，确保 payload 不可篡改
        String signature = HMACUtils.sign(body, sharedSecret);

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(targetUrl))
                .timeout(Duration.ofSeconds(timeoutSeconds))
                .header("Content-Type",    "application/json; charset=UTF-8")
                .header(HEADER_EVENT_ID,   eventId)    // ← 与 Body envelope.eventId 完全一致
                .header(HEADER_EVENT_TYPE, eventType)
                .header(HEADER_TIMESTAMP,  timestamp)
                .header(HEADER_SIGNATURE,  signature)
                .POST(HttpRequest.BodyPublishers.ofByteArray(body))
                .build();

        return httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }

    // ----------------------------------------------------------------
    // 死信落库
    // ----------------------------------------------------------------

    /**
     * 将失败投递信息写入死信表。
     *
     * <p>Payload 以 UTF-8 字符串形式存储，方便管理员在后台直接查看 JSON 内容。
     * 死信 Service 内部使用 {@code REQUIRES_NEW} 独立事务，
     * 即使异步线程上下文无事务也能正常写入。
     */
    private void persistDeadLetter(String eventId, String eventType,
                                   String targetUrl, byte[] body,
                                   String lastError, int attempts) {
        try {
            String payloadStr = new String(body, StandardCharsets.UTF_8);
            WebhookDeadLetterRecord record = WebhookDeadLetterRecord.of(
                    eventId, eventType, targetUrl, payloadStr, lastError, attempts);
            deadLetterService.save(record);
        } catch (Exception e) {
            // 死信落库自身也失败时，至少保证日志可查，告警系统可据此触发人工介入
            log.error("[WebHook][死信] 落库异常！eventId={} payload={}",
                    eventId, new String(body, StandardCharsets.UTF_8), e);
        }
    }

    // ----------------------------------------------------------------
    // 辅助方法
    // ----------------------------------------------------------------

    /**
     * 指数退避 + Full Jitter，防止多路 WebHook 在下游恢复瞬间同时打爆接收方。
     *
     * <p>公式：{@code min(baseDelayMs × 2^(attempt-1) + random(0, baseDelayMs), 30000)}
     *
     * @param attempt 当前重试轮次（从 1 开始）
     * @return 实际等待毫秒数
     */
    private long calcBackoff(int attempt) {
        long exponential = baseDelayMs * (1L << (attempt - 1));     // 指数增长部分
        long jitter      = (long) (Math.random() * baseDelayMs);    // 随机抖动，防惊群
        return Math.min(exponential + jitter, 30_000L);             // 上限 30 秒
    }

    private boolean isSuccess(int status)     { return status >= 200 && status < 300; }
    private boolean isClientError(int status) { return status >= 400 && status < 500; }
    private String  truncate(String s)        { return s != null && s.length() > 200 ? s.substring(0, 200) + "…" : s; }

    private void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
