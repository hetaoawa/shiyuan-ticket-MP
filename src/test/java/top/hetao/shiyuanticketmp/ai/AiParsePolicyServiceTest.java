package top.hetao.shiyuanticketmp.ai;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.data.redis.core.ZSetOperations;

import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AiParsePolicyServiceTest {
    @SuppressWarnings("unchecked")
    @Test
    void cacheKeySeparatesTenantUserModeAndSchemaAndHitConsumesNoQuota() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        ValueOperations<String, String> values = mock(ValueOperations.class);
        ZSetOperations<String, String> zsets = mock(ZSetOperations.class);
        when(redis.opsForValue()).thenReturn(values);
        when(redis.opsForZSet()).thenReturn(zsets);
        when(redis.getExpire(anyString(), eq(TimeUnit.SECONDS))).thenReturn(-2L);
        when(values.get(anyString())).thenAnswer(invocation ->
                invocation.<String>getArgument(0).startsWith("ai:parse:cache:")
                        ? "{\"ok\":true}" : null);
        AtomicInteger calls = new AtomicInteger();

        Object result = new AiParsePolicyService(redis, new ObjectMapper()).execute(
                12L, "34", "batch", "v7", "input", () -> {
                    calls.incrementAndGet();
                    return "{}";
                });

        assertThat(result).isEqualTo(Map.of("ok", true));
        assertThat(calls).hasValue(0);
        ArgumentCaptor<String> key = ArgumentCaptor.forClass(String.class);
        verify(values).get(key.capture());
        assertThat(key.getValue()).startsWith("ai:parse:cache:12:34:batch:v7:");
        verify(values, never()).increment(anyString());
    }

    @SuppressWarnings("unchecked")
    @Test
    void cacheMissConsumesOneQuotaAndCachesValidatedResult() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        ValueOperations<String, String> values = mock(ValueOperations.class);
        ZSetOperations<String, String> zsets = mock(ZSetOperations.class);
        when(redis.opsForValue()).thenReturn(values);
        when(redis.opsForZSet()).thenReturn(zsets);
        when(redis.getExpire(anyString(), eq(TimeUnit.SECONDS))).thenReturn(-2L);
        when(values.get(anyString())).thenReturn(null);
        when(values.increment(anyString())).thenReturn(1L);

        Object result = new AiParsePolicyService(redis, new ObjectMapper()).execute(
                12L, "34", "single", "v1", "input", () -> "{\"ok\":true}");

        assertThat(result).isEqualTo(Map.of("ok", true));
        verify(values).increment("ai:parse:rate:12:34");
        verify(values).set(anyString(), eq("{\"ok\":true}"), eq(600L), eq(TimeUnit.SECONDS));
    }

    @SuppressWarnings("unchecked")
    @Test
    void cached418CountsARejectionAndFifthRejectionCreatesTenMinuteBlock() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        ValueOperations<String, String> values = mock(ValueOperations.class);
        ZSetOperations<String, String> zsets = mock(ZSetOperations.class);
        when(redis.opsForValue()).thenReturn(values);
        when(redis.opsForZSet()).thenReturn(zsets);
        when(redis.getExpire(anyString(), eq(TimeUnit.SECONDS))).thenReturn(-2L);
        when(values.get(anyString())).thenAnswer(invocation ->
                invocation.<String>getArgument(0).startsWith("ai:parse:cache:")
                        ? "__418__" : null);
        when(zsets.zCard("ai:parse:rej:12:34")).thenReturn(5L);

        assertThatThrownBy(() -> new AiParsePolicyService(redis, new ObjectMapper()).execute(
                12L, "34", "batch", "v1", "input", () -> "{}"))
                .isInstanceOf(AiParseService.AiParseException.class)
                .hasMessage("不合法的输入");

        verify(zsets).add(eq("ai:parse:rej:12:34"), anyString(), org.mockito.ArgumentMatchers.anyDouble());
        verify(values).set("ai:parse:blocked:12:34", "1", 600L, TimeUnit.SECONDS);
        verify(values, never()).increment(anyString());
    }

    @SuppressWarnings("unchecked")
    @Test
    void singleAndBatchModesSharePrincipalRateQuotaButKeepSeparateCacheKeys() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        ValueOperations<String, String> values = mock(ValueOperations.class);
        ZSetOperations<String, String> zsets = mock(ZSetOperations.class);
        when(redis.opsForValue()).thenReturn(values);
        when(redis.opsForZSet()).thenReturn(zsets);
        when(redis.getExpire(anyString(), eq(TimeUnit.SECONDS))).thenReturn(-2L);
        when(values.get(anyString())).thenReturn(null);
        when(values.increment("ai:parse:rate:12:34")).thenReturn(1L, 2L);
        AiParsePolicyService service = new AiParsePolicyService(redis, new ObjectMapper());

        service.execute(12L, "34", "single", "v1", "one", () -> "{\"mode\":\"single\"}");
        service.execute(12L, "34", "batch", "v2", "two", () -> "{\"mode\":\"batch\"}");

        verify(values, times(2)).increment("ai:parse:rate:12:34");
        ArgumentCaptor<String> cacheKeys = ArgumentCaptor.forClass(String.class);
        verify(values, times(2)).get(cacheKeys.capture());
        assertThat(cacheKeys.getAllValues()).anyMatch(key -> key.startsWith("ai:parse:cache:12:34:single:v1:"));
        assertThat(cacheKeys.getAllValues()).anyMatch(key -> key.startsWith("ai:parse:cache:12:34:batch:v2:"));
    }
}
