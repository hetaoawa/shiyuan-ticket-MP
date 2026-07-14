package top.hetao.shiyuanticketmp.tenant.setting.service;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import top.hetao.shiyuanticketmp.tenant.integration.IntegrationType;
import top.hetao.shiyuanticketmp.tenant.integration.TenantIntegrationResolver;

/**
 * Compatibility facade for runtime feature switches.
 *
 * <p>The legacy settings API and {@code sys_tenant_setting} writer were removed. All values
 * are now resolved from the tenant integration configuration so callers cannot observe a
 * successful legacy update that has no runtime effect.</p>
 */
@Service
public class TenantIntegrationSettingService {

    private final TenantIntegrationResolver integrationResolver;

    public TenantIntegrationSettingService(TenantIntegrationResolver integrationResolver) {
        this.integrationResolver = integrationResolver;
    }

    @Transactional(readOnly = true)
    public boolean externalInboundEnabled(Long tenantId) {
        var config = integrationResolver.resolve(tenantId, IntegrationType.CARGO_OWNER);
        return config.enabled() && config.bool("externalInboundEnabled", false);
    }

    @Transactional(readOnly = true)
    public boolean externalCloseCallbackEnabled(Long tenantId) {
        var config = integrationResolver.resolve(tenantId, IntegrationType.CARGO_OWNER);
        return config.enabled() && config.bool("externalCloseCallbackEnabled", false);
    }

    @Transactional(readOnly = true)
    public boolean dingTalkPushEnabled(Long tenantId) {
        return integrationResolver.resolve(tenantId, IntegrationType.DINGTALK).enabled();
    }
}
