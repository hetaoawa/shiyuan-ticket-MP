package top.hetao.shiyuanticketmp.ai;

import cn.dev33.satoken.stp.StpUtil;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * AI 智能解析控制器。
 *
 * <p>接收前端传回的待解析文本，调用大模型解析后返回结构化 JSON。
 * 限登录使用，每用户每分钟最多 10 次（Redis 滑动窗口限速）。
 */
@RestController
@RequestMapping("/api/ai")
public class AiParseController {

    private static final Logger log = LoggerFactory.getLogger(AiParseController.class);
    private static final int RATE_LIMIT = 10;
    private static final int RATE_WINDOW_SECONDS = 60;
    private static final int MAX_INPUT_LENGTH = 300;

    private final AiParseService aiParseService;
    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;

    public AiParseController(AiParseService aiParseService, StringRedisTemplate redisTemplate,
                              ObjectMapper objectMapper) {
        this.aiParseService = aiParseService;
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;
    }

    /**
     * 智能解析文本，返回结构化 JSON。
     *
     * <p>请求体：{@code {"text": "待解析的文本内容"}}
     * <p>响应：AI 解析后的 JSON（包含 type、trackingNo、address 等字段）
     */
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

        // 限速检查
        String userId = StpUtil.getLoginIdAsString();
        String rateLimitKey = "ai:parse:rate:" + userId;
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

        try {
            String result = aiParseService.parse(text);
            // 解析为 JSON 对象返回（而非字符串）
            Object data = objectMapper.readValue(result, Object.class);
            return ResponseEntity.ok(Map.of(
                    "code", 200,
                    "message", "解析成功",
                    "data", data
            ));
        } catch (AiParseService.AiParseException e) {
            log.warn("[AI解析] 用户{}解析失败: {}", userId, e.getMessage());
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
}
