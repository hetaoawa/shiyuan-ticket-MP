package top.hetao.shiyuanticketmp.webhook.receiver.handler;

import com.fasterxml.jackson.databind.JsonNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * WebHook 事件路由器。
 *
 * <p>自动收集所有 {@link WebhookEventHandler} 实现，按 eventType 建立路由表。
 * 收到事件时查找对应处理器执行，未注册的事件类型记录警告日志后跳过。
 */
@Component
public class WebhookEventRouter {

    private static final Logger log = LoggerFactory.getLogger(WebhookEventRouter.class);

    private final Map<String, WebhookEventHandler> handlerMap = new HashMap<>();

    /**
     * 构造时自动注入所有 handler 实现，建立 eventType -> handler 映射。
     *
     * @param handlers Spring 容器中所有 WebhookEventHandler 实现
     */
    public WebhookEventRouter(List<WebhookEventHandler> handlers) {
        for (WebhookEventHandler handler : handlers) {
            String type = handler.eventType();
            if (handlerMap.containsKey(type)) {
                log.warn("[WebHook路由] eventType={} 存在重复处理器: {} 与 {}",
                        type, handlerMap.get(type).getClass().getSimpleName(),
                        handler.getClass().getSimpleName());
            }
            handlerMap.put(type, handler);
            log.info("[WebHook路由] 注册处理器 eventType={} -> {}", type, handler.getClass().getSimpleName());
        }
    }

    /**
     * 路由并执行事件处理。
     *
     * @param event 解析后的 JSON 根节点，必须包含 eventType 字段
     */
    public void route(JsonNode event) {
        String eventType = event.has("eventType") ? event.get("eventType").asText() : "unknown";
        String eventId = event.has("eventId") ? event.get("eventId").asText() : "unknown";

        WebhookEventHandler handler = handlerMap.get(eventType);
        if (handler == null) {
            log.warn("[WebHook路由] 未找到处理器 eventId={} eventType={}", eventId, eventType);
            return;
        }

        try {
            handler.handle(event);
            log.info("[WebHook路由] 处理完成 eventId={} eventType={}", eventId, eventType);
        } catch (Exception e) {
            log.error("[WebHook路由] 处理失败 eventId={} eventType={}", eventId, eventType, e);
            throw e; // 重新抛出，让 Worker 决定是否重试
        }
    }
}
