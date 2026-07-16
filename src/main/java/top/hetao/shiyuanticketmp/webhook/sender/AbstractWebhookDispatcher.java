package top.hetao.shiyuanticketmp.webhook.sender;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import top.hetao.shiyuanticketmp.webhook.deadletter.WebhookDeadLetterRecord;
import top.hetao.shiyuanticketmp.webhook.deadletter.WebhookDeadLetterService;
import top.hetao.shiyuanticketmp.common.context.TenantContext;
import top.hetao.shiyuanticketmp.workorder.event.WorkOrderEvent;

import java.net.http.HttpClient;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Value;

/**
 * WebHook 调度器抽象基类。
 *
 * <p>封装公共的重试逻辑（指数退避 + Full Jitter）、死信落库、辅助方法。
 * 子类只需实现 {@link #buildRequestUrl}、{@link #buildRequestBody}、
 * {@link #doSend}、{@link #isSuccess} 四个模板方法即可适配不同的下游通道。
 */
public abstract class AbstractWebhookDispatcher {

    protected final Logger log = LoggerFactory.getLogger(getClass());

    protected final ObjectMapper objectMapper;
    protected final WebhookDeadLetterService deadLetterService;

    @Value("${webhook.max-retry:5}")
    protected int maxRetry;

    @Value("${webhook.base-delay-ms:500}")
    protected long baseDelayMs;

    @Value("${webhook.timeout-seconds:10}")
    protected int timeoutSeconds;

    protected final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();

    protected AbstractWebhookDispatcher(ObjectMapper objectMapper,
                                         WebhookDeadLetterService deadLetterService) {
        this.objectMapper = objectMapper;
        this.deadLetterService = deadLetterService;
    }

    // ----------------------------------------------------------------
    // 公共入口
    // ----------------------------------------------------------------

    /**
     * 异步投递事件（首次投递）。
     *
     * @param eventType 事件类型
     * @param payload   业务数据对象
     */
    public void dispatch(String eventType, Object payload) {
        String eventId = UUID.randomUUID().toString();
        log.info("[{}] 开始投递 eventId={} type={}", channelName(), eventId, eventType);

        try {
            byte[] body = buildRequestBody(eventId, eventType, payload);
            if (payload instanceof WorkOrderEvent event) {
                doDispatchWithRetry(eventType, eventId, body,
                        event.getConversationId(), event.getSenderStaffId());
            } else {
                doDispatchWithRetry(eventType, eventId, body);
            }
        } catch (Exception e) {
            log.error("[{}] payload 序列化失败，eventId={}", channelName(), eventId, e);
        }
    }

    /**
     * 同步原始字节重投（死信补偿）。每次均重新读取当前配置并构造 URL/签名。
     * 失败只返回结果，不再落一条重复死信。
     */
    public DispatchResult retryRaw(String eventType, String eventId, byte[] rawBody) {
        log.info("[{}][补偿] 开始重投 eventId={} type={}", channelName(), eventId, eventType);
        return executeWithRetry(eventType, eventId, rawBody, false, null, null);
    }

    // ----------------------------------------------------------------
    // 模板方法（子类实现）
    // ----------------------------------------------------------------

    /** 通道名称，用于日志前缀 */
    protected abstract String channelName();

    /** 写入死信记录的稳定通道代码。 */
    protected abstract String channelCode();

    /** 构造完整的请求 URL（含查询参数） */
    protected abstract String buildRequestUrl();

    /** 构造请求体字节数组 */
    protected abstract byte[] buildRequestBody(String eventId, String eventType,
                                                Object payload) throws Exception;

    /** 发送单次 HTTP 请求 */
    protected abstract HttpResponse<String> doSend(String url, byte[] body,
                                                   String eventId) throws Exception;

    /** 判断响应是否表示成功 */
    protected abstract boolean isSuccess(HttpResponse<String> response);

    // ----------------------------------------------------------------
    // 核心：指数退避重试
    // ----------------------------------------------------------------

    protected void doDispatchWithRetry(String eventType, String eventId, byte[] body) {
        doDispatchWithRetry(eventType, eventId, body, null, null);
    }

    protected void doDispatchWithRetry(String eventType, String eventId, byte[] body,
                                       String conversationId, String senderStaffId) {
        executeWithRetry(eventType, eventId, body, true, conversationId, senderStaffId);
    }

    private DispatchResult executeWithRetry(String eventType, String eventId, byte[] body,
                                             boolean persistOnFailure,
                                             String conversationId, String senderStaffId) {
        int attempt = 0;
        String lastError = "未知错误";
        String url = channelName() + ":configuration-error";
        Integer lastStatusCode = null;

        while (attempt <= maxRetry) {
            attempt++;
            lastStatusCode = null;
            try {
                // URL and credential validation are deliberately lazy and happen inside the
                // retry boundary so a disabled/unused channel never blocks application startup.
                url = buildRequestUrl();
                HttpResponse<String> response = doSend(url, body, eventId);
                lastStatusCode = response.statusCode();

                if (isSuccess(response)) {
                    log.info("[{}] 投递成功 eventId={} attempt={} status={}",
                            channelName(), eventId, attempt, response.statusCode());
                    return DispatchResult.succeeded(response.statusCode());
                }

                lastError = "HTTP " + response.statusCode() + ": " + truncate(response.body());

                if (response.statusCode() >= 400 && response.statusCode() < 500) {
                    log.error("[{}] 4xx 不可重试 eventId={} status={}",
                            channelName(), eventId, response.statusCode());
                    break;
                }

                log.warn("[{}] 5xx 错误 eventId={} attempt={}/{} status={}",
                        channelName(), eventId, attempt, maxRetry + 1, response.statusCode());

            } catch (Exception e) {
                lastError = e.getClass().getSimpleName() + ": " + e.getMessage();
                log.warn("[{}] 网络异常 eventId={} attempt={}/{} error={}",
                        channelName(), eventId, attempt, maxRetry + 1, e.getMessage());
            }

            if (attempt > maxRetry) {
                log.error("[{}] 已达最大重试次数，eventId={}", channelName(), eventId);
                break;
            }

            long backoff = calcBackoff(attempt);
            log.info("[{}] 退避等待 {}ms eventId={}", channelName(), backoff, eventId);
            sleep(backoff);
        }

        if (persistOnFailure) {
            persistDeadLetter(eventId, eventType, url, body, lastError, attempt,
                    conversationId, senderStaffId);
        }
        return DispatchResult.failed(lastStatusCode, lastError);
    }

    // ----------------------------------------------------------------
    // 辅助方法
    // ----------------------------------------------------------------

    protected long calcBackoff(int attempt) {
        long exponential = baseDelayMs * (1L << (attempt - 1));
        long jitter = (long) (Math.random() * baseDelayMs);
        return Math.min(exponential + jitter, 30_000L);
    }

    protected void persistDeadLetter(String eventId, String eventType,
                                      String targetUrl, byte[] body,
                                      String lastError, int attempts,
                                      String conversationId, String senderStaffId) {
        try {
            String payloadStr = new String(body, StandardCharsets.UTF_8);
            WebhookDeadLetterRecord record = WebhookDeadLetterRecord.of(
                    eventId, eventType, targetUrl, payloadStr, lastError, attempts);
            record.setTenantId(TenantContext.requireTenantId());
            record.setChannel(channelCode());
            record.setConversationId(conversationId);
            record.setSenderStaffId(senderStaffId);
            deadLetterService.save(record);
        } catch (Exception e) {
            log.error("[{}][死信] 落库异常！eventId={}", channelName(), eventId, e);
        }
    }

    protected String truncate(String s) {
        return s != null && s.length() > 200 ? s.substring(0, 200) + "…" : s;
    }

    protected void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
