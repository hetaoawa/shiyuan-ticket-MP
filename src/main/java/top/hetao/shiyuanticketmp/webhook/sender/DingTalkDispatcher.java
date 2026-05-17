package top.hetao.shiyuanticketmp.webhook.sender;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import top.hetao.shiyuanticketmp.webhook.deadletter.WebhookDeadLetterService;
import top.hetao.shiyuanticketmp.workorder.event.WorkOrderEvent;
import top.hetao.shiyuanticketmp.workorder.enums.WorkOrderType;

import jakarta.annotation.PostConstruct;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 钉钉自定义机器人 WebHook 调度器。
 *
 * <p>使用加签方式验证身份，支持单条和批量（Markdown）两种投递模式。
 *
 * <p>成功响应：{@code {"errcode": 0, "errmsg": "ok"}}
 */
@Component
public class DingTalkDispatcher extends AbstractWebhookDispatcher {

    private static final String DINGTALK_API_URL = "https://oapi.dingtalk.com/robot/send";

    @Value("${webhook.dingtalk.access-token}")
    private String accessToken;

    @Value("${webhook.dingtalk.secret}")
    private String secret;

    @Value("${webhook.dingtalk.work-order-detail-base-url:}")
    private String workOrderDetailBaseUrl;

    private String normalizedDetailBaseUrl;

    public DingTalkDispatcher(ObjectMapper objectMapper, WebhookDeadLetterService deadLetterService) {
        super(objectMapper, deadLetterService);
    }

    @PostConstruct
    void init() {
        if (workOrderDetailBaseUrl == null || workOrderDetailBaseUrl.isBlank()) {
            throw new IllegalArgumentException("[DingTalk] webhook.dingtalk.work-order-detail-base-url 未配置或为空，钉钉查看详情链接需要完整可打开的 URL");
        }
        String trimmed = workOrderDetailBaseUrl.trim();
        if (trimmed.startsWith("\"") || trimmed.startsWith("'")
                || trimmed.endsWith("\"") || trimmed.endsWith("'")) {
            throw new IllegalArgumentException("[DingTalk] webhook.dingtalk.work-order-detail-base-url 不应包含引号，当前值: " + workOrderDetailBaseUrl);
        }
        URI uri;
        try {
            uri = URI.create(trimmed);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("[DingTalk] webhook.dingtalk.work-order-detail-base-url 格式非法: " + workOrderDetailBaseUrl, e);
        }
        String scheme = uri.getScheme();
        if (scheme == null) {
            throw new IllegalArgumentException("[DingTalk] webhook.dingtalk.work-order-detail-base-url 缺少协议（scheme），当前值: " + workOrderDetailBaseUrl);
        }
        if (!"http".equalsIgnoreCase(scheme) && !"https".equalsIgnoreCase(scheme)) {
            throw new IllegalArgumentException("[DingTalk] webhook.dingtalk.work-order-detail-base-url 协议必须为 http/https，当前值: " + workOrderDetailBaseUrl);
        }
        if (uri.getHost() == null || uri.getHost().isBlank()) {
            throw new IllegalArgumentException("[DingTalk] webhook.dingtalk.work-order-detail-base-url 缺少主机名（host），当前值: " + workOrderDetailBaseUrl);
        }
        normalizedDetailBaseUrl = trimmed.endsWith("/")
                ? trimmed.substring(0, trimmed.length() - 1)
                : trimmed;
        log.info("[DingTalk] 工单详情 base URL 已校验: {}", normalizedDetailBaseUrl);
    }

    @Override
    protected String channelName() {
        return "DingTalk";
    }

    @Override
    protected String buildRequestUrl() {
        return buildSignedUrl();
    }

    @Override
    protected byte[] buildRequestBody(String eventId, String eventType,
                                       Object payload) throws Exception {
        String message = formatSingleMessage(eventType, payload);
        Map<String, Object> body = Map.of(
                "msgtype", "markdown",
                "markdown", Map.of("title", "工单通知", "text", message)
        );
        return objectMapper.writeValueAsBytes(body);
    }

    @Override
    protected HttpResponse<String> doSend(String url, byte[] body) throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofSeconds(timeoutSeconds))
                .header("Content-Type", "application/json; charset=UTF-8")
                .POST(HttpRequest.BodyPublishers.ofByteArray(body))
                .build();
        return httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }

    @Override
    protected boolean isSuccess(HttpResponse<String> response) {
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            return false;
        }
        try {
            JsonNode node = objectMapper.readTree(response.body());
            return node.has("errcode") && node.get("errcode").asInt() == 0;
        } catch (Exception e) {
            log.warn("[DingTalk] 解析响应失败: {}", response.body());
            return false;
        }
    }

    // ----------------------------------------------------------------
    // 批量投递（聚合器调用）
    // ----------------------------------------------------------------

    /**
     * 批量投递：将多条事件合并为一条 Markdown 消息发送。
     */
    public void dispatchBatch(List<WorkOrderEvent> events) {
        if (events == null || events.isEmpty()) return;

        String eventId = UUID.randomUUID().toString();
        log.info("[DingTalk] 批量投递 eventId={} 条数={}", eventId, events.size());

        try {
            String markdown = formatBatchMarkdown(events);
            Map<String, Object> body = Map.of(
                    "msgtype", "markdown",
                    "markdown", Map.of("title", "工单通知", "text", markdown)
            );
            byte[] bodyBytes = objectMapper.writeValueAsBytes(body);
            doDispatchWithRetry("BATCH", eventId, bodyBytes);
        } catch (Exception e) {
            log.error("[DingTalk] 批量消息序列化失败 eventId={}", eventId, e);
        }
    }

    // ----------------------------------------------------------------
    // URL 构造
    // ----------------------------------------------------------------

    private String buildSignedUrl() {
        try {
            long timestamp = System.currentTimeMillis();
            String sign = generateSign(timestamp, secret);
            return DINGTALK_API_URL
                    + "?access_token=" + accessToken
                    + "&timestamp=" + timestamp
                    + "&sign=" + URLEncoder.encode(sign, StandardCharsets.UTF_8);
        } catch (Exception e) {
            log.error("[DingTalk] 生成签名URL失败", e);
            throw new RuntimeException("生成钉钉签名URL失败", e);
        }
    }

    private String generateSign(long timestamp, String secret) throws Exception {
        String stringToSign = timestamp + "\n" + secret;
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        byte[] signData = mac.doFinal(stringToSign.getBytes(StandardCharsets.UTF_8));
        return new String(Base64.getEncoder().encode(signData), StandardCharsets.UTF_8);
    }

    // ----------------------------------------------------------------
    // 消息格式化
    // ----------------------------------------------------------------

    /**
     * 批量 Markdown 格式：多条事件合并为一条消息。
     */
    private String formatBatchMarkdown(List<WorkOrderEvent> events) {
        StringBuilder sb = new StringBuilder();
        sb.append("### 工单通知（").append(events.size()).append("条）\n\n");

        for (int i = 0; i < events.size(); i++) {
            WorkOrderEvent e = events.get(i);
            sb.append("---\n\n");
            sb.append("#### ").append(i + 1).append(". ").append(actionLabel(e.getStatus())).append("\n\n");
            sb.append("- **工单ID**：").append(e.getWorkOrderId()).append("\n");
            sb.append("- **标题**：").append(nvl(e.getTitle())).append("\n");
            sb.append("- **类型**：").append(typeLabel(e.getType())).append("\n");
            sb.append("- **创建时间**：").append(e.getCreatedAtFormatted()).append("\n");

            if (e.getTrackingNo() != null && !e.getTrackingNo().isBlank()) {
                sb.append("- **运单号**：").append(e.getTrackingNo()).append("\n");
            }
            if (e.getAssigneeId() != null) {
                sb.append("- **处理人ID**：").append(e.getAssigneeId()).append("\n");
            }

            // 扩展信息
            Map<String, Object> extra = e.getExtra();
            if (extra != null) {
                if (extra.containsKey("resolution")) {
                    sb.append("- **处理结论**：").append(extra.get("resolution")).append("\n");
                }
                if (extra.containsKey("reason")) {
                    sb.append("- **驳回原因**：").append(extra.get("reason")).append("\n");
                }
            }

            // 处理链接
            String detailUrl = normalizedDetailBaseUrl + "/workorders/detail/" + e.getWorkOrderId();
            sb.append("- **处理链接**：[查看详情](").append(detailUrl).append(")\n");
            sb.append("\n");
        }

        return sb.toString();
    }

    /**
     * 单条消息 Markdown 格式（兼容旧路径）。
     */
    private String formatSingleMessage(String eventType, Object payload) {
        if (payload instanceof WorkOrderEvent) {
            WorkOrderEvent e = (WorkOrderEvent) payload;
            return formatBatchMarkdown(List.of(e));
        }
        // fallback
        return "工单事件：" + eventType;
    }

    private String actionLabel(String status) {
        return switch (status) {
            case "PENDING" -> "工单已创建";
            case "IN_PROGRESS" -> "工单已派发";
            case "ASSIGNED" -> "工单已派发";
            case "CLOSED" -> "工单已关闭";
            case "REJECTED" -> "工单已驳回";
            default -> status;
        };
    }

    private String typeLabel(WorkOrderType type) {
        if (type == null) return "未分类";
        return type.getLabel();
    }

    private String nvl(String s) {
        return s != null ? s : "";
    }
}
