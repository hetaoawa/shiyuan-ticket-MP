package top.hetao.shiyuanticketmp.express;

import cn.dev33.satoken.stp.StpUtil;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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
 * 5 分钟内重复拉取同一单号使用 Redis 缓存，不再请求外部接口。
 */
@RestController
@RequestMapping("/api/express")
public class ExpressController {

    private static final Logger log = LoggerFactory.getLogger(ExpressController.class);

    private static final int RATE_LIMIT = 10;
    private static final int RATE_LIMIT_WINDOW = 60;
    private static final int CACHE_TTL_SECONDS = 300; // 5 分钟

    private final ExpressService expressService;
    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;

    public ExpressController(ExpressService expressService, StringRedisTemplate redisTemplate,
                              ObjectMapper objectMapper) {
        this.expressService = expressService;
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;
    }

    /**
     * 查询物流轨迹（强制刷新）。
     *
     * <p>需登录，限速：每用户每分钟 10 次。
     * 5 分钟内重复查询同一单号返回 Redis 缓存数据。
     * 查询成功后，若物流状态非无物流/待揽收/已揽收，则落库存储。
     */
    @PostMapping("/trace")
    public Map<String, Object> queryTrace(@RequestBody ExpressTraceRequest request) {
        String userId = StpUtil.getLoginIdAsString();
        checkRateLimit(userId);

        if (request.getTrackingNo() == null || request.getTrackingNo().isBlank()) {
            throw new WorkOrderException("物流单号不能为空");
        }

        String trackingNo = request.getTrackingNo().trim();

        // 1. 检查 Redis 缓存（5 分钟）
        String cacheKey = "express:trace:" + trackingNo;
        String cached = redisTemplate.opsForValue().get(cacheKey);
        if (cached != null) {
            log.info("[物流查询] 命中 Redis 缓存 trackingNo={}", trackingNo);
            try {
                ExpressTraceResponse cachedResult = objectMapper.readValue(cached, ExpressTraceResponse.class);
                Map<String, Object> result = new HashMap<>();
                result.put("code", 200);
                result.put("data", cachedResult);
                result.put("cached", true);
                return result;
            } catch (JsonProcessingException e) {
                log.warn("[物流查询] 缓存反序列化失败，重新查询 trackingNo={}", trackingNo);
            }
        }

        // 2. 调用外部接口
        ExpressTraceResponse trace = expressService.queryTrace(
                trackingNo,
                request.getMobileLast4(),
                request.getCpCode()
        );

        // 3. 写入 Redis 缓存（5 分钟）
        try {
            String json = objectMapper.writeValueAsString(trace);
            redisTemplate.opsForValue().set(cacheKey, json, CACHE_TTL_SECONDS, TimeUnit.SECONDS);
        } catch (JsonProcessingException e) {
            log.warn("[物流查询] 缓存写入失败 trackingNo={}", trackingNo, e);
        }

        // 4. 符合条件时落库
        expressService.saveToDbIfQualified(trackingNo, trace);

        Map<String, Object> result = new HashMap<>();
        result.put("code", 200);
        result.put("data", trace);
        return result;
    }

    /**
     * 查询物流轨迹（DB 缓存优先，12 小时有效期，已签收永不过期）。
     *
     * <p>逻辑：
     * <ol>
     *   <li>DB 中有数据且未过期 → 直接返回</li>
     *   <li>DB 中有数据但已过期 → 重新获取并更新 DB</li>
     *   <li>DB 中无数据 → 调用外部接口获取</li>
     *   <li>获取到的物流状态为无物流/待揽收/已揽收 → 不落库</li>
     *   <li>获取到的物流状态有轨迹或非 ACCEPT → 落库（12h 有效期）</li>
     *   <li>获取到的物流状态为已签收 → 落库（永不过期）</li>
     * </ol>
     */
    @PostMapping("/tracked")
    public Map<String, Object> queryTracked(@RequestBody ExpressTraceRequest request) {
        String userId = StpUtil.getLoginIdAsString();
        checkRateLimit(userId);

        if (request.getTrackingNo() == null || request.getTrackingNo().isBlank()) {
            throw new WorkOrderException("物流单号不能为空");
        }

        String trackingNo = request.getTrackingNo().trim();

        ExpressTraceResponse trace = expressService.getTrackedTrace(
                trackingNo,
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
     * 限速检查（Redis 滑动窗口）。
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
