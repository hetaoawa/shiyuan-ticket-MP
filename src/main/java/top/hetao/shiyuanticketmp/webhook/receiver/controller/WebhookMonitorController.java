package top.hetao.shiyuanticketmp.webhook.receiver.controller;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import top.hetao.shiyuanticketmp.webhook.receiver.WebhookEventQueueService;

import java.util.HashMap;
import java.util.Map;

/**
 * WebHook 队列监控接口（管理员用）。
 */
@RestController
@RequestMapping("/api/admin/webhook")
public class WebhookMonitorController {

    private final WebhookEventQueueService queueService;

    public WebhookMonitorController(WebhookEventQueueService queueService) {
        this.queueService = queueService;
    }

    /**
     * 获取队列状态。
     */
    @GetMapping("/queue/status")
    public Map<String, Object> queueStatus() {
        Map<String, Object> result = new HashMap<>();
        result.put("code", 200);
        result.put("queueSize", queueService.queueSize());
        result.put("processingSize", queueService.processingSize());
        return result;
    }
}
