package top.hetao.shiyuanticketmp.webhook.receiver;

import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import top.hetao.shiyuanticketmp.util.HMACUtils;

import java.io.IOException;
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

    public WebhookReceiverController(WebhookEventQueueService queueService) {
        this.queueService = queueService;
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
        if (signature == null || timestamp == null || eventId == null) {
            log.warn("[WebHook接收] 缺少必要 Header");
            return ResponseEntity.badRequest().body(Map.of(
                    "code", 400,
                    "message", "缺少必要 Header"
            ));
        }

        // 3. 验证时间戳（防重放攻击）
        try {
            long requestTime = Long.parseLong(timestamp);
            long currentTime = System.currentTimeMillis() / 1000L;
            if (Math.abs(currentTime - requestTime) > TIMESTAMP_TOLERANCE_SECONDS) {
                log.warn("[WebHook接收] 时间戳过期 eventId={} diff={}s", eventId,
                        Math.abs(currentTime - requestTime));
                return ResponseEntity.badRequest().body(Map.of(
                        "code", 400,
                        "message", "请求时间戳超出允许范围"
                ));
            }
        } catch (NumberFormatException e) {
            log.warn("[WebHook接收] 时间戳格式错误 eventId={}", eventId);
            return ResponseEntity.badRequest().body(Map.of(
                    "code", 400,
                    "message", "时间戳格式错误"
            ));
        }

        // 4. 验证 HMAC-SHA256 签名
        byte[] bodyBytes = body.getBytes(StandardCharsets.UTF_8);
        String expectedSignature = HMACUtils.sign(bodyBytes, sharedSecret);
        if (!HMACUtils.safeEquals(signature, expectedSignature)) {
            log.warn("[WebHook接收] 签名验证失败 eventId={}", eventId);
            return ResponseEntity.status(401).body(Map.of(
                    "code", 401,
                    "message", "签名验证失败"
            ));
        }

        // 5. 入队（快速返回）
        queueService.push(body);
        log.info("[WebHook接收] 事件已入队 eventId={} eventType={}", eventId, eventType);

        return ResponseEntity.ok(Map.of(
                "code", 200,
                "message", "事件已接收",
                "eventId", eventId
        ));
    }
}
