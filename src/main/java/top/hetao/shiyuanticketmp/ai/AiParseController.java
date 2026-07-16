package top.hetao.shiyuanticketmp.ai;

import cn.dev33.satoken.stp.StpUtil;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import top.hetao.shiyuanticketmp.common.context.TenantContext;

import java.util.LinkedHashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/ai")
public class AiParseController {
    private static final int MAX_SINGLE_INPUT_LENGTH = 300;

    private final AiParseService aiParseService;
    private final AiParsePolicyService policyService;
    private final AiBatchParseService batchParseService;

    public AiParseController(AiParseService aiParseService,
                             AiParsePolicyService policyService,
                             AiBatchParseService batchParseService) {
        this.aiParseService = aiParseService;
        this.policyService = policyService;
        this.batchParseService = batchParseService;
    }

    @PostMapping("/parse")
    public ResponseEntity<Map<String, Object>> parse(@RequestBody Map<String, String> request) {
        String text = request.get("text");
        if (text == null || text.isBlank()) {
            return badRequest("text 不能为空");
        }
        String normalized = text.length() > MAX_SINGLE_INPUT_LENGTH
                ? text.substring(0, MAX_SINGLE_INPUT_LENGTH) : text;
        return executePolicy("single", "v2", normalized,
                () -> aiParseService.parse(normalized));
    }

    @PostMapping("/parse-batch")
    public ResponseEntity<Map<String, Object>> parseBatch(@RequestBody BatchAiParseRequest request) {
        try {
            // Validate before consuming cache/rate quota.
            batchParseService.validateInput(request.getText(), request.getExpectedType());
            String normalized = request.getText().lines()
                    .map(String::trim).filter(line -> !line.isEmpty())
                    .reduce((a, b) -> a + "\n" + b).orElse("");
            String cacheInput = (request.getExpectedType() == null ? "" : request.getExpectedType())
                    + "\n" + normalized;
            return executePolicy("batch", AiBatchParseService.SCHEMA_VERSION, cacheInput,
                    () -> batchParseService.parseAndValidate(
                            request.getText(), request.getExpectedType()));
        } catch (AiParseService.AiParseException e) {
            return badRequest(e.getMessage());
        }
    }

    private ResponseEntity<Map<String, Object>> executePolicy(
            String mode, String schema, String input, java.util.function.Supplier<String> operation) {
        try {
            Object data = policyService.execute(
                    TenantContext.requireTenantId(), StpUtil.getLoginIdAsString(),
                    mode, schema, input, operation);
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("code", 200);
            body.put("message", "解析成功");
            body.put("data", data);
            return ResponseEntity.ok(body);
        } catch (AiParsePolicyService.AiPolicyException e) {
            return ResponseEntity.status(429)
                    .header(HttpHeaders.RETRY_AFTER, String.valueOf(e.getRetryAfterSeconds()))
                    .body(Map.of("code", 429, "message", e.getMessage()));
        } catch (AiParseService.AiParseException e) {
            return badRequest(e.getMessage());
        } catch (Exception e) {
            return ResponseEntity.internalServerError().body(Map.of(
                    "code", 500, "message", "解析处理异常"));
        }
    }

    private ResponseEntity<Map<String, Object>> badRequest(String message) {
        return ResponseEntity.badRequest().body(Map.of("code", 400, "message", message));
    }
}
