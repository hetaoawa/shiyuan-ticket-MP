package top.hetao.shiyuanticketmp.ai;

import cn.dev33.satoken.stp.StpUtil;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * AI 智能解析控制器。
 *
 * <p>接收前端传回的待解析文本，调用大模型解析后返回结构化 JSON。
 * 限登录使用，每用户每分钟最多 10 次（Redis 滑动窗口限速）。
 *
 * <p>新增缓存与 418 拒绝限流：
 * <ul>
 *   <li>相同文本 10 分钟内返回缓存结果，不重复调用模型（缓存按用户隔离）</li>
 *   <li>模型返回 418（不合法输入）时缓存拒绝标记，缓存命中也计入拒绝次数</li>
 *   <li>单用户 60 秒内累计 5 次 418 拒绝则封禁 10 分钟</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/ai")
public class AiParseController {

    private static final Logger log = LoggerFactory.getLogger(AiParseController.class);
    private static final int RATE_LIMIT = 10;
    private static final int RATE_WINDOW_SECONDS = 60;
    private static final int MAX_INPUT_LENGTH = 300;

    private static final int REJECTION_THRESHOLD = 5;
    private static final int REJECTION_WINDOW_SECONDS = 60;
    private static final int BLOCK_DURATION_SECONDS = 600;
    private static final int CACHE_TTL_SECONDS = 600;

    private static final String CACHE_PREFIX = "ai:parse:cache:";
    private static final String BLOCK_PREFIX = "ai:parse:blocked:";
    private static final String REJECTION_PREFIX = "ai:parse:rej:";
    private static final String RATE_PREFIX = "ai:parse:rate:";

    private static final String CACHE_418_MARKER = "__418__";

    private final AiParseService aiParseService;
    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;

    public AiParseController(AiParseService aiParseService, StringRedisTemplate redisTemplate,
                              ObjectMapper objectMapper) {
        this.aiParseService = aiParseService;
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;
    }

    @PostMapping("/parse")
    public ResponseEntity<Map<String, Object>> parse(@RequestBody Map<String, String> request) {
        String text = request.get("text");
        if (text == null || text.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of(
                    "code", 400,
                    "message", "text 不能为空"
            ));
        }

        // 截断超长输入
        if (text.length() > MAX_INPUT_LENGTH) {
            text = text.substring(0, MAX_INPUT_LENGTH);
        }

        String userId = StpUtil.getLoginIdAsString();

        // 1. 检查封禁
        String blockKey = BLOCK_PREFIX + userId;
        Long blockTtl = redisTemplate.getExpire(blockKey, TimeUnit.SECONDS);
        if (blockTtl != null && blockTtl > 0) {
            return ResponseEntity.status(429).body(Map.of(
                    "code", 429,
                    "message", "请求过于频繁，请 " + blockTtl + " 秒后再试"
            ));
        }

        // 2. 计算文本哈希，检查缓存（缓存命中不消耗通用速率配额）
        String textHash = sha256Hex(text);
        String cacheKey = CACHE_PREFIX + userId + ":" + textHash;
        String cached = redisTemplate.opsForValue().get(cacheKey);

        if (cached != null) {
            if (CACHE_418_MARKER.equals(cached)) {
                // 缓存命中 418，记录拒绝并返回（418 计数仍生效）
                log.info("[AI解析] 缓存命中 418, userId={}, hash={}", userId, textHash);
                recordRejectionAndMaybeBlock(userId);
                return ResponseEntity.badRequest().body(Map.of(
                        "code", 400,
                        "message", "不合法的输入"
                ));
            } else {
                // 缓存命中成功结果
                log.info("[AI解析] 缓存命中, userId={}, hash={}", userId, textHash);
                try {
                    Object data = objectMapper.readValue(cached, Object.class);
                    return ResponseEntity.ok(Map.of(
                            "code", 200,
                            "message", "解析成功",
                            "data", data
                    ));
                } catch (Exception e) {
                    log.warn("[AI解析] 缓存内容解析失败，删除坏缓存后继续调用模型", e);
                    redisTemplate.delete(cacheKey);
                }
            }
        }

        // 3. 全局速率限制（仅在缓存未命中时消耗配额）
        String rateLimitKey = RATE_PREFIX + userId;
        Long count = redisTemplate.opsForValue().increment(rateLimitKey);
        if (count != null && count == 1) {
            redisTemplate.expire(rateLimitKey, RATE_WINDOW_SECONDS, TimeUnit.SECONDS);
        }
        if (count != null && count > RATE_LIMIT) {
            return ResponseEntity.status(429).body(Map.of(
                    "code", 429,
                    "message", "请求过于频繁，请稍后再试（每分钟最多" + RATE_LIMIT + "次）"
            ));
        }

        // 4. 调用模型
        try {
            String result = aiParseService.parse(text);
            Object data = objectMapper.readValue(result, Object.class);
            // 缓存成功结果
            redisTemplate.opsForValue().set(cacheKey, result, CACHE_TTL_SECONDS, TimeUnit.SECONDS);
            return ResponseEntity.ok(Map.of(
                    "code", 200,
                    "message", "解析成功",
                    "data", data
            ));
        } catch (AiParseService.AiParseException e) {
            log.warn("[AI解析] 用户{}解析失败: {}", userId, e.getMessage());
            // 判定为不合法输入时缓存 418 标记并记录拒绝
            if ("不合法的输入".equals(e.getMessage())) {
                redisTemplate.opsForValue().set(cacheKey, CACHE_418_MARKER, CACHE_TTL_SECONDS, TimeUnit.SECONDS);
                recordRejectionAndMaybeBlock(userId);
            }
            return ResponseEntity.badRequest().body(Map.of(
                    "code", 400,
                    "message", e.getMessage()
            ));
        } catch (Exception e) {
            log.error("[AI解析] 处理异常", e);
            return ResponseEntity.internalServerError().body(Map.of(
                    "code", 500,
                    "message", "解析处理异常"
            ));
        }
    }

    /**
     * 记录一次 418 拒绝，如果窗口内累计达到阈值则封禁用户。
     */
    private void recordRejectionAndMaybeBlock(String userId) {
        String rejectionKey = REJECTION_PREFIX + userId;
        long now = System.currentTimeMillis();
        long windowStart = now - (long) REJECTION_WINDOW_SECONDS * 1000;

        // 使用 ZSET 记录拒绝时间戳
        redisTemplate.opsForZSet().add(rejectionKey, now + ":" + UUID.randomUUID(), now);
        // 移除窗口外的旧记录
        redisTemplate.opsForZSet().removeRangeByScore(rejectionKey, 0, windowStart);
        // 设置 key 过期（略大于窗口）
        redisTemplate.expire(rejectionKey, REJECTION_WINDOW_SECONDS + 10, TimeUnit.SECONDS);

        Long rejCount = redisTemplate.opsForZSet().zCard(rejectionKey);
        if (rejCount != null && rejCount >= REJECTION_THRESHOLD) {
            String blockKey = BLOCK_PREFIX + userId;
            redisTemplate.opsForValue().set(blockKey, "1", BLOCK_DURATION_SECONDS, TimeUnit.SECONDS);
            log.warn("[AI解析] 用户{} 418 拒绝达到{}次，封禁{}秒", userId, REJECTION_THRESHOLD, BLOCK_DURATION_SECONDS);
        }
    }

    private static String sha256Hex(String input) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(input.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (Exception e) {
            throw new RuntimeException("SHA-256 not available", e);
        }
    }
}
