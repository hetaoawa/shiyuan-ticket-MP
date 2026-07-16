package top.hetao.shiyuanticketmp.tenant.integration;

import java.util.Collections;
import java.util.Map;

public record ResolvedIntegration(long tenantId, IntegrationType type, boolean enabled,
                                  long configVersion, Map<String, Object> publicConfig,
                                  Map<String, String> secrets) {
    public ResolvedIntegration {
        publicConfig = Collections.unmodifiableMap(publicConfig);
        secrets = Collections.unmodifiableMap(secrets);
    }
    public String text(String key) { Object value = publicConfig.get(key); return value == null ? null : String.valueOf(value); }
    public String secret(String key) { return secrets.get(key); }
    public boolean bool(String key, boolean defaultValue) {
        Object value = publicConfig.get(key);
        if (value == null) return defaultValue;
        if (value instanceof Number number) return number.intValue() != 0;
        String text = String.valueOf(value);
        return "1".equals(text) || Boolean.parseBoolean(text);
    }
    public int integer(String key, int defaultValue) {
        Object value = publicConfig.get(key); if (value == null) return defaultValue;
        try { return Integer.parseInt(String.valueOf(value)); } catch (NumberFormatException e) { return defaultValue; }
    }
}
