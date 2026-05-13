package top.hetao.shiyuanticketmp.webhook.deadletter.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import cn.dev33.satoken.stp.StpUtil;
import org.springframework.web.bind.annotation.*;
import top.hetao.shiyuanticketmp.webhook.deadletter.WebhookDeadLetterService;
import top.hetao.shiyuanticketmp.webhook.deadletter.WebhookDeadLetterService.DeadLetterPageResult;

import java.util.HashMap;
import java.util.Map;

/**
 * 死信管理 REST API 控制器。
 *
 * <p>仅系统管理员可访问，用于查看和处理 WebHook 投递失败的死信记录。
 */
@RestController
@RequestMapping("/api/admin/deadletters")
public class DeadLetterController {

    private final WebhookDeadLetterService deadLetterService;

    public DeadLetterController(WebhookDeadLetterService deadLetterService) {
        this.deadLetterService = deadLetterService;
    }

    /**
     * 分页查询待处理死信列表。
     *
     * <p>需要 {@code deadletter:view} 权限。
     */
    @GetMapping
    @SaCheckPermission("deadletter:view")
    public Map<String, Object> list(@RequestParam(defaultValue = "1") int page,
                                    @RequestParam(defaultValue = "10") int pageSize) {
        DeadLetterPageResult pageResult = deadLetterService.listPending(page, pageSize);

        Map<String, Object> result = new HashMap<>();
        result.put("code", 200);
        result.put("data", pageResult.records());
        result.put("total", pageResult.total());
        result.put("page", page);
        result.put("pageSize", pageSize);
        return result;
    }

    /**
     * 手动重试死信投递。
     *
     * <p>需要 {@code deadletter:retry} 权限。
     *
     * @param id 死信记录 ID
     */
    @PostMapping("/{id}/retry")
    @SaCheckPermission("deadletter:retry")
    public Map<String, Object> retry(@PathVariable Long id) {
        String operator = StpUtil.getLoginIdAsString();
        deadLetterService.retry(id, operator);

        Map<String, Object> result = new HashMap<>();
        result.put("code", 200);
        result.put("message", "重试投递已触发");
        return result;
    }

    /**
     * 标记死信为已忽略。
     *
     * <p>需要 {@code deadletter:ignore} 权限。
     *
     * @param id 死信记录 ID
     */
    @PostMapping("/{id}/ignore")
    @SaCheckPermission("deadletter:ignore")
    public Map<String, Object> ignore(@PathVariable Long id) {
        String operator = StpUtil.getLoginIdAsString();
        deadLetterService.ignore(id, operator);

        Map<String, Object> result = new HashMap<>();
        result.put("code", 200);
        result.put("message", "已标记忽略");
        return result;
    }
}
