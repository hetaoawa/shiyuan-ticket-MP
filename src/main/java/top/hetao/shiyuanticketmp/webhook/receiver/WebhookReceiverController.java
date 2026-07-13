package top.hetao.shiyuanticketmp.webhook.receiver;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import top.hetao.shiyuanticketmp.util.HMACUtils;

import java.nio.charset.StandardCharsets;
import java.util.Map;

/**
 * WebHook 接收端控制器（Queue-First 模式）。
 *
 * <p>职责：
 * <ol>
 *   <li>验证 HMAC-SHA256 签名（防篡改 + 防伪造）</li>
 *   <li>验证时间戳（5 分钟窗口防重放）</li>
 *   <li>将事件推入 Redis 队列</li>
 *   <li>立即返回 200</li>
 * </ol>
 *
 * <p>实际业务处理由 {@link WebhookEventWorker} 异步完成。
 */
@RestController
@RequestMapping("/api/webhook")
public class WebhookReceiverController {

    private static final Logger log = LoggerFactory.getLogger(WebhookReceiverController.class);

    private static final String HEADER_SIGNATURE = "X-Signature";
    private static final String HEADER_TIMESTAMP = "X-Timestamp";
    private static final String HEADER_EVENT_ID = "X-Event-Id";
    private static final String HEADER_EVENT_TYPE = "X-Event-Type";

    /** 签名验证时间窗口（秒），超过此时间差的请求视为重放攻击 */
    private static final long TIMESTAMP_TOLERANCE_SECONDS = 300; // 5 分钟

    @Value("${webhook.secret}")
    private String sharedSecret;

    private final WebhookEventQueueService queueService;
    private final ObjectMapper objectMapper;

    public WebhookReceiverController(WebhookEventQueueService queueService,
                                     ObjectMapper objectMapper) {
        this.queueService = queueService;
        this.objectMapper = objectMapper;
    }

    /**
     * 接收 WebHook 事件。
     *
     * <p>验证签名后立即入队返回，不阻塞调用方。
     */
    @PostMapping
    public ResponseEntity<Map<String, Object>> receive(HttpServletRequest request,
                                                       @RequestBody String body) {
        // 1. 读取 Header
        String signature = request.getHeader(HEADER_SIGNATURE);
        String timestamp = request.getHeader(HEADER_TIMESTAMP);
        String eventId = request.getHeader(HEADER_EVENT_ID);
        String eventType = request.getHeader(HEADER_EVENT_TYPE);

        // 2. 校验必要 Header
        if (isBlank(signature) || isBlank(timestamp) || isBlank(eventId) || isBlank(eventType)) {
            log.warn("[WebHook接收] 缺少必要 Header");
            return ResponseEntity.badRequest().body(Map.of(
                    "code", 400,
                    "message", "缺少必要 Header"
            ));
        }

        // 3. 先验证签名，再解析或暴露信封校验细节，避免形成格式探测 oracle。
        byte[] bodyBytes = body.getBytes(StandardCharsets.UTF_8);
        String expectedSignature = HMACUtils.sign(bodyBytes, sharedSecret);
        if (!HMACUtils.safeEquals(signature, expectedSignature)) {
            log.warn("[WebHook接收] 签名验证失败 eventId={}", eventId);
            return ResponseEntity.status(401).body(Map.of(
                    "code", 401,
                    "message", "签名验证失败"
            ));
        }

        // 4. 路由和防重放元数据必须来自已签名信封，并与 Header 严格一致。
        JsonNode envelope;
        try {
            envelope = objectMapper.readTree(body);
        } catch (Exception e) {
            return badEnvelope();
        }
        String bodyEventId = requiredText(envelope, "eventId");
        String bodyEventType = requiredText(envelope, "eventType");
        JsonNode bodyTimestampNode = envelope == null ? null : envelope.get("timestamp");
        if (bodyEventId == null || bodyEventType == null || bodyTimestampNode == null
                || !bodyTimestampNode.isIntegralNumber() || !bodyTimestampNode.canConvertToLong()) {
            return badEnvelope();
        }

        long headerTimestamp;
        long bodyTimestamp = bodyTimestampNode.longValue();
        try {
            headerTimestamp = Long.parseLong(timestamp);
        } catch (NumberFormatException e) {
            return badEnvelope();
        }
        if (!eventId.equals(bodyEventId) || !eventType.equals(bodyEventType)
                || headerTimestamp != bodyTimestamp) {
            log.warn("[WebHook接收] Header/Body 元数据不一致 eventId={}", eventId);
            return badEnvelope();
        }

        long currentTime = System.currentTimeMillis() / 1000L;
        if (bodyTimestamp < currentTime - TIMESTAMP_TOLERANCE_SECONDS
                || bodyTimestamp > currentTime + TIMESTAMP_TOLERANCE_SECONDS) {
            log.warn("[WebHook接收] 已签名 Body 时间戳过期 eventId={}", eventId);
            return ResponseEntity.badRequest().body(Map.of(
                    "code", 400,
                    "message", "请求时间戳超出允许范围"
            ));
        }

        // 5. 原子预留 eventId；重复请求幂等返回 200，但不会重复写入 Stream。
        String reservationToken = queueService.reserveEvent(eventId);
        if (reservationToken == null) {
            log.info("[WebHook接收] 重复事件已忽略 eventId={}", eventId);
            return ResponseEntity.ok(Map.of(
                    "code", 200,
                    "message", "事件已接收",
                    "eventId", eventId
            ));
        }
        try {
            queueService.push(eventId, eventType, timestamp, body);
        } catch (RuntimeException | Error pushFailure) {
            try {
                queueService.releaseEventReservation(eventId, reservationToken);
            } catch (RuntimeException releaseFailure) {
                pushFailure.addSuppressed(releaseFailure);
                log.error("[WebHook接收] 释放事件预留失败 eventId={}", eventId, releaseFailure);
            }
            throw pushFailure;
        }
        log.info("[WebHook接收] 事件已入队 eventId={} eventType={}", eventId, eventType);

        return ResponseEntity.ok(Map.of(
                "code", 200,
                "message", "事件已接收",
                "eventId", eventId
        ));
    }

    private ResponseEntity<Map<String, Object>> badEnvelope() {
        return ResponseEntity.badRequest().body(Map.of(
                "code", 400,
                "message", "WebHook 事件信封无效"
        ));
    }

    private static String requiredText(JsonNode envelope, String field) {
        if (envelope == null || !envelope.isObject()) {
            return null;
        }
        JsonNode value = envelope.get(field);
        if (value == null || !value.isTextual() || value.textValue().isBlank()) {
            return null;
        }
        return value.textValue();
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
