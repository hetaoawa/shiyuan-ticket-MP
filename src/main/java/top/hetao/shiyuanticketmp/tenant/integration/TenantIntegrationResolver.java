package top.hetao.shiyuanticketmp.tenant.integration;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import top.hetao.shiyuanticketmp.common.context.TenantContext;

import java.time.Duration;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class TenantIntegrationResolver {
    private static final Logger log = LoggerFactory.getLogger(TenantIntegrationResolver.class);
    private static final long LOCAL_TTL_MS = 5_000;
    private final TenantIntegrationMapper mapper;
    private final IntegrationSecretCrypto crypto;
    private final ObjectMapper objectMapper;
    private final StringRedisTemplate redis;
    private final ConcurrentHashMap<Key, CacheEntry> local = new ConcurrentHashMap<>();

    public TenantIntegrationResolver(TenantIntegrationMapper mapper, IntegrationSecretCrypto crypto,
                                     ObjectMapper objectMapper, StringRedisTemplate redis) {
        this.mapper = mapper; this.crypto = crypto; this.objectMapper = objectMapper; this.redis = redis;
    }

    public ResolvedIntegration resolve(long tenantId, IntegrationType type) {
        if (tenantId <= 0) throw new IllegalArgumentException("A business tenant id is required");
        Key key = new Key(tenantId, type);
        long now = System.currentTimeMillis();
        CacheEntry hit = local.get(key);
        String generation = remoteGeneration(key);
        if (hit != null && hit.expiresAt > now && (generation == null || generation.equals(hit.generation))) return hit.value;
        ResolvedIntegration value = load(tenantId, type);
        local.put(key, new CacheEntry(value, now + LOCAL_TTL_MS, generation));
        return value;
    }

    public void invalidate(long tenantId, IntegrationType type) {
        Key key = new Key(tenantId, type);
        local.remove(key);
        try { redis.opsForValue().set(redisKey(key), Long.toString(System.nanoTime()), Duration.ofDays(1)); }
        catch (RuntimeException e) { log.warn("Redis unavailable while invalidating tenant integration tenantId={} type={}", tenantId, type); }
    }

    private ResolvedIntegration load(long tenantId, IntegrationType type) {
        SysTenantIntegration row;
        try (TenantContext.Scope ignored = TenantContext.useTenant(tenantId)) {
            row = mapper.selectOne(new LambdaQueryWrapper<SysTenantIntegration>()
                    .eq(SysTenantIntegration::getIntegrationType, type.name()).last("LIMIT 1"));
        }
        if (row == null) return new ResolvedIntegration(tenantId, type, false, 0, Map.of(), Map.of());
        try {
            Map<String,Object> pub = row.getPublicConfigJson() == null ? new LinkedHashMap<>() :
                    objectMapper.readValue(row.getPublicConfigJson(), new TypeReference<>() {});
            Map<String,IntegrationSecretCrypto.EncryptedValue> encrypted = row.getSecretConfigJson() == null ? Map.of() :
                    objectMapper.readValue(row.getSecretConfigJson(), new TypeReference<>() {});
            Map<String,String> secrets = new HashMap<>();
            encrypted.forEach((field, value) -> secrets.put(field, crypto.decrypt(tenantId, type, field, value)));
            return new ResolvedIntegration(tenantId, type, Boolean.TRUE.equals(row.getEnabled()),
                    row.getConfigVersion(), pub, secrets);
        } catch (Exception e) { throw new IllegalStateException("Invalid stored integration configuration", e); }
    }

    private String remoteGeneration(Key key) {
        try { return redis.opsForValue().get(redisKey(key)); }
        catch (RuntimeException e) { return null; }
    }
    private String redisKey(Key key) { return "tenant:integration:version:" + key.tenantId + ":" + key.type; }
    private record Key(long tenantId, IntegrationType type) {}
    private record CacheEntry(ResolvedIntegration value, long expiresAt, String generation) {}
}
