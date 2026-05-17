package top.hetao.shiyuanticketmp.webhook.sender;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import top.hetao.shiyuanticketmp.webhook.deadletter.WebhookDeadLetterService;
import top.hetao.shiyuanticketmp.workorder.event.WorkOrderEvent;
import top.hetao.shiyuanticketmp.workorder.enums.WorkOrderType;

import jakarta.annotation.PostConstruct;
import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 货主侧 WebHook 调度器。
 *
 * <p>消息格式（纯文本，非 Markdown）：
 * <pre>{@code
 * {
 *   "message": "纯文本内容",
 *   "room_id": "群ID（来自工单的 conversationId）",
 *   "senderStaffId": "被@人的userId（来自工单的 senderStaffId）"
 * }
 * }</pre>
 *
 * <p>{@code room_id} 和 {@code senderStaffId} 从工单事件中动态获取，
 * 而非配置文件静态配置，确保每条通知回溯到正确的群和人。
 *
 * <p>成功响应：{@code {"success": true, "msg": "OK"}}
 */
@Component
public class CargoOwnerDispatcher extends AbstractWebhookDispatcher {

    @Value("${webhook.cargo-owner.url}")
    private String targetUrl;

    @Value("${webhook.cargo-owner.authorization}")
    private String authorization;

    private String normalizedUrl;

    public CargoOwnerDispatcher(ObjectMapper objectMapper, WebhookDeadLetterService deadLetterService) {
        super(objectMapper, deadLetterService);
    }

    @PostConstruct
    void init() {
        if (targetUrl == null || targetUrl.isBlank()) {
            throw new IllegalArgumentException("[CargoOwner] webhook.cargo-owner.url 未配置或为空");
        }
        String trimmed = targetUrl.trim();
        if (trimmed.startsWith("\"") || trimmed.startsWith("'")
                || trimmed.endsWith("\"") || trimmed.endsWith("'")) {
            throw new IllegalArgumentException("[CargoOwner] webhook.cargo-owner.url 不应包含引号，当前值: " + targetUrl);
        }
        URI uri;
        try {
            uri = URI.create(trimmed);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("[CargoOwner] webhook.cargo-owner.url 格式非法: " + targetUrl, e);
        }
        String scheme = uri.getScheme();
        if (scheme == null) {
            throw new IllegalArgumentException("[CargoOwner] webhook.cargo-owner.url 缺少协议（scheme），当前值: " + targetUrl);
        }
        if (!"http".equalsIgnoreCase(scheme) && !"https".equalsIgnoreCase(scheme)) {
            throw new IllegalArgumentException("[CargoOwner] webhook.cargo-owner.url 协议必须为 http/https，当前值: " + targetUrl);
        }
        if (uri.getHost() == null || uri.getHost().isBlank()) {
            throw new IllegalArgumentException("[CargoOwner] webhook.cargo-owner.url 缺少主机名（host），当前值: " + targetUrl);
        }
        normalizedUrl = trimmed;
        log.info("[CargoOwner] 货主 WebHook URL 已校验: {}", normalizedUrl);
    }

    @Override
    protected String channelName() {
        return "CargoOwner";
    }

    @Override
    protected String buildRequestUrl() {
        return normalizedUrl;
    }

    @Override
    protected byte[] buildRequestBody(String eventId, String eventType,
                                       Object payload) throws Exception {
        String message = formatSingleText(eventType, payload);
        Map<String, Object> body = buildMessageBody(message, null, null);
        return objectMapper.writeValueAsBytes(body);
    }

    @Override
    protected HttpResponse<String> doSend(String url, byte[] body) throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofSeconds(timeoutSeconds))
                .header("Content-Type", "application/json; charset=UTF-8")
                .header("Authorization", authorization)
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
            return node.has("success") && node.get("success").asBoolean();
        } catch (Exception e) {
            log.warn("[CargoOwner] 解析响应失败: {}", response.body());
            return false;
        }
    }

    // ----------------------------------------------------------------
    // 批量投递（聚合器调用）
    // ----------------------------------------------------------------

    /**
     * 批量投递：将多条事件合并为一条纯文本消息。
     *
     * <p>取第一条事件的 conversationId/senderStaffId 作为 room_id/senderStaffId。
     * 若同一批次涉及多个群，以第一条为准（实际场景中同一群的消息通常在同一窗口内）。
     */
    public void dispatchBatch(List<WorkOrderEvent> events) {
        if (events == null || events.isEmpty()) return;

        String eventId = UUID.randomUUID().toString();
        log.info("[CargoOwner] 批量投递 eventId={} 条数={}", eventId, events.size());

        try {
            String message = formatBatchText(events);

            // 从事件中取群ID和发送人ID
            String roomId = null;
            String staffId = null;
            for (WorkOrderEvent e : events) {
                if (roomId == null && e.getConversationId() != null) {
                    roomId = e.getConversationId();
                }
                if (staffId == null && e.getSenderStaffId() != null) {
                    staffId = e.getSenderStaffId();
                }
                if (roomId != null && staffId != null) break;
            }

            byte[] body = objectMapper.writeValueAsBytes(buildMessageBody(message, roomId, staffId));
            doDispatchWithRetry("BATCH", eventId, body);
        } catch (Exception e) {
            log.error("[CargoOwner] 批量消息序列化失败 eventId={}", eventId, e);
        }
    }

    // ----------------------------------------------------------------
    // 消息体构造
    // ----------------------------------------------------------------

    private Map<String, Object> buildMessageBody(String message, String roomId, String staffId) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("message", message);
        if (roomId != null && !roomId.isBlank()) {
            body.put("room_id", roomId);
        }
        if (staffId != null && !staffId.isBlank()) {
            body.put("senderStaffId", staffId);
        }
        return body;
    }

    // ----------------------------------------------------------------
    // 纯文本格式化
    // ----------------------------------------------------------------

    private String formatBatchText(List<WorkOrderEvent> events) {
        StringBuilder sb = new StringBuilder();
        sb.append("【工单通知】共").append(events.size()).append("条\n\n");

        for (int i = 0; i < events.size(); i++) {
            WorkOrderEvent e = events.get(i);
            if (i > 0) sb.append("\n────────────\n\n");

            sb.append(i + 1).append(". ").append(actionLabel(e.getStatus())).append("\n");
            sb.append("工单ID：").append(e.getWorkOrderId()).append("\n");
            sb.append("标题：").append(nvl(e.getTitle())).append("\n");
            sb.append("类型：").append(typeLabel(e.getType())).append("\n");
            sb.append("创建时间：").append(e.getCreatedAtFormatted()).append("\n");

            if (e.getTrackingNo() != null && !e.getTrackingNo().isBlank()) {
                sb.append("运单号：").append(e.getTrackingNo()).append("\n");
            }
            if (e.getAssigneeId() != null) {
                sb.append("处理人ID：").append(e.getAssigneeId()).append("\n");
            }

            Map<String, Object> extra = e.getExtra();
            if (extra != null) {
                if (extra.containsKey("resolution")) {
                    sb.append("处理结论：").append(extra.get("resolution")).append("\n");
                }
                if (extra.containsKey("reason")) {
                    sb.append("驳回原因：").append(extra.get("reason")).append("\n");
                }
            }
        }

        return sb.toString();
    }

    private String formatSingleText(String eventType, Object payload) {
        if (payload instanceof WorkOrderEvent) {
            return formatBatchText(List.of((WorkOrderEvent) payload));
        }
        return "工单事件：" + eventType;
    }

    private String actionLabel(String status) {
        return switch (status) {
            case "PENDING" -> "工单已创建";
            case "ASSIGNED" -> "工单已派发";
            case "CLOSED" -> "工单已关闭";
            case "REJECTED" -> "工单已驳回";
            case "COMMENT" -> "工单有新留言";
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
