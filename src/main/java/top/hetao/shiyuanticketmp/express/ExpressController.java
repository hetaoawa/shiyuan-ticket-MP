package top.hetao.shiyuanticketmp.express;

import cn.dev33.satoken.stp.StpUtil;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.web.bind.annotation.*;
import top.hetao.shiyuanticketmp.express.dto.ExpressTraceRequest;
import top.hetao.shiyuanticketmp.express.dto.ExpressTraceResponse;
import top.hetao.shiyuanticketmp.workorder.exception.WorkOrderException;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * 物流轨迹查询控制器。
 *
 * <p>需登录后使用，每个用户每分钟最多查询 10 次。
 */
@RestController
@RequestMapping("/api/express")
public class ExpressController {

    private static final int RATE_LIMIT = 10;
    private static final int RATE_LIMIT_WINDOW = 60; // 秒

    private final ExpressService expressService;
    private final StringRedisTemplate redisTemplate;

    public ExpressController(ExpressService expressService, StringRedisTemplate redisTemplate) {
        this.expressService = expressService;
        this.redisTemplate = redisTemplate;
    }

    /**
     * 查询物流轨迹。
     *
     * <p>需登录，限速：每用户每分钟 10 次。
     */
    @PostMapping("/trace")
    public Map<String, Object> queryTrace(@RequestBody ExpressTraceRequest request) {
        // 1. 获取当前用户 ID（Sa-Token 已自动校验登录）
        String userId = StpUtil.getLoginIdAsString();

        // 2. 限速检查
        checkRateLimit(userId);

        // 3. 参数校验
        if (request.getTrackingNo() == null || request.getTrackingNo().isBlank()) {
            throw new WorkOrderException("物流单号不能为空");
        }

        // 4. 查询物流轨迹
        ExpressTraceResponse trace = expressService.queryTrace(
                request.getTrackingNo(),
                request.getMobileLast4(),
                request.getCpCode()
        );

        Map<String, Object> result = new HashMap<>();
        result.put("code", 200);
        result.put("data", trace);
        return result;
    }

    /**
     * 获取快递公司列表。
     *
     * <p>需登录，用于前端下拉选择。
     */
    @GetMapping("/companies")
    public Map<String, Object> getCompanies() {
        ExpressCodeRegistry registry = expressService.getCodeRegistry();

        Map<String, Object> result = new HashMap<>();
        result.put("code", 200);
        result.put("data", registry.getAllCodes().stream()
                .map(code -> {
                    Map<String, String> item = new HashMap<>();
                    item.put("code", code);
                    item.put("name", registry.getCompanyName(code));
                    return item;
                })
                .toList());
        return result;
    }

    /**
     * 限速检查。
     *
     * <p>使用 Redis 滑动窗口计数器，每个用户每分钟最多 {@value RATE_LIMIT} 次。
     */
    private void checkRateLimit(String userId) {
        String key = "express:rate_limit:" + userId;
        Long count = redisTemplate.opsForValue().increment(key);
        if (count == null) {
            count = 1L;
        }
        if (count == 1) {
            redisTemplate.expire(key, RATE_LIMIT_WINDOW, TimeUnit.SECONDS);
        }
        if (count > RATE_LIMIT) {
            throw new WorkOrderException("查询过于频繁，请稍后再试");
        }
    }
}
