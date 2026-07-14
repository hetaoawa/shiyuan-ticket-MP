package top.hetao.shiyuanticketmp.ai;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

@Service
public class AiParsePolicyService {
    private static final int RATE_LIMIT = 10;
    private static final int RATE_WINDOW_SECONDS = 60;
    private static final int REJECTION_THRESHOLD = 5;
    private static final int REJECTION_WINDOW_SECONDS = 60;
    private static final int BLOCK_DURATION_SECONDS = 600;
    private static final int CACHE_TTL_SECONDS = 600;
    private static final String CACHE_418_MARKER = "__418__";

    private final StringRedisTemplate redis;
    private final ObjectMapper objectMapper;

    public AiParsePolicyService(StringRedisTemplate redis, ObjectMapper objectMapper) {
        this.redis = redis;
        this.objectMapper = objectMapper;
    }

    public Object execute(Long tenantId, String userId, String mode, String schemaVersion,
                          String normalizedInput, Supplier<String> operation) {
        String principalScope = tenantId + ":" + userId;
        String cacheScope = principalScope + ":" + mode + ":" + schemaVersion;
        String blockKey = "ai:parse:blocked:" + principalScope;
        Long blockTtl = redis.getExpire(blockKey, TimeUnit.SECONDS);
        if (blockTtl != null && blockTtl > 0) {
            throw AiPolicyException.blocked(blockTtl);
        }

        String cacheKey = "ai:parse:cache:" + cacheScope + ":" + sha256(normalizedInput);
        String cached = redis.opsForValue().get(cacheKey);
        if (cached != null) {
            if (CACHE_418_MARKER.equals(cached)) {
                recordRejection(principalScope);
                throw AiParseService.AiParseException.invalidInput();
            }
            try {
                return objectMapper.readValue(cached, Object.class);
            } catch (Exception e) {
                redis.delete(cacheKey);
            }
        }

        String rateKey = "ai:parse:rate:" + principalScope;
        Long count = redis.opsForValue().increment(rateKey);
        if (count != null && count == 1) {
            redis.expire(rateKey, RATE_WINDOW_SECONDS, TimeUnit.SECONDS);
        }
        if (count != null && count > RATE_LIMIT) {
            Long ttl = redis.getExpire(rateKey, TimeUnit.SECONDS);
            throw AiPolicyException.rateLimited(ttl == null || ttl <= 0 ? RATE_WINDOW_SECONDS : ttl);
        }

        try {
            String result = operation.get();
            Object data = objectMapper.readValue(result, Object.class);
            redis.opsForValue().set(cacheKey, result, CACHE_TTL_SECONDS, TimeUnit.SECONDS);
            return data;
        } catch (AiParseService.AiParseException e) {
            if (e.isInvalidInput()) {
                redis.opsForValue().set(cacheKey, CACHE_418_MARKER, CACHE_TTL_SECONDS, TimeUnit.SECONDS);
                recordRejection(principalScope);
            }
            throw e;
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new AiParseService.AiParseException("解析结果不是合法 JSON");
        }
    }

    private void recordRejection(String scope) {
        String rejectionKey = "ai:parse:rej:" + scope;
        long now = System.currentTimeMillis();
        redis.opsForZSet().add(rejectionKey, now + ":" + UUID.randomUUID(), now);
        redis.opsForZSet().removeRangeByScore(
                rejectionKey, 0, now - REJECTION_WINDOW_SECONDS * 1000L);
        redis.expire(rejectionKey, REJECTION_WINDOW_SECONDS + 10L, TimeUnit.SECONDS);
        Long count = redis.opsForZSet().zCard(rejectionKey);
        if (count != null && count >= REJECTION_THRESHOLD) {
            redis.opsForValue().set("ai:parse:blocked:" + scope, "1",
                    BLOCK_DURATION_SECONDS, TimeUnit.SECONDS);
        }
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    public static class AiPolicyException extends RuntimeException {
        private final long retryAfterSeconds;

        private AiPolicyException(String message, long retryAfterSeconds) {
            super(message);
            this.retryAfterSeconds = retryAfterSeconds;
        }

        static AiPolicyException blocked(long seconds) {
            return new AiPolicyException("请求过于频繁，请稍后再试", seconds);
        }

        static AiPolicyException rateLimited(long seconds) {
            return new AiPolicyException("每分钟最多请求 10 次", seconds);
        }

        public long getRetryAfterSeconds() {
            return retryAfterSeconds;
        }
    }
}
