package top.hetao.shiyuanticketmp.tenant.setting.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import top.hetao.shiyuanticketmp.common.context.TenantContext;
import top.hetao.shiyuanticketmp.tenant.setting.controller.dto.TenantIntegrationSettings;
import top.hetao.shiyuanticketmp.tenant.setting.controller.dto.TenantIntegrationSettingsRequest;
import top.hetao.shiyuanticketmp.tenant.setting.entity.SysTenantSetting;
import top.hetao.shiyuanticketmp.tenant.setting.mapper.SysTenantSettingMapper;
import top.hetao.shiyuanticketmp.tenant.service.TenantLifecycleGuard;
import top.hetao.shiyuanticketmp.workorder.exception.WorkOrderException;
import top.hetao.shiyuanticketmp.tenant.integration.IntegrationType;
import top.hetao.shiyuanticketmp.tenant.integration.TenantIntegrationResolver;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
public class TenantIntegrationSettingService {

    public static final String EXTERNAL_INBOUND_ENABLED = "externalInboundEnabled";
    public static final String EXTERNAL_CLOSE_CALLBACK_ENABLED = "externalCloseCallbackEnabled";
    public static final String DING_TALK_PUSH_ENABLED = "dingTalkPushEnabled";

    private static final List<String> SUPPORTED_KEYS = List.of(
            EXTERNAL_INBOUND_ENABLED,
            EXTERNAL_CLOSE_CALLBACK_ENABLED,
            DING_TALK_PUSH_ENABLED
    );

    private final SysTenantSettingMapper settingMapper;
    private final TenantLifecycleGuard tenantLifecycleGuard;
    private final TenantIntegrationResolver integrationResolver;

    public TenantIntegrationSettingService(SysTenantSettingMapper settingMapper,
                                           TenantLifecycleGuard tenantLifecycleGuard,
                                           TenantIntegrationResolver integrationResolver) {
        this.settingMapper = settingMapper;
        this.tenantLifecycleGuard = tenantLifecycleGuard;
        this.integrationResolver = integrationResolver;
    }

    @Transactional(readOnly = true)
    public TenantIntegrationSettings get(Long tenantId) {
        requireBusinessTenant(tenantId);
        try (TenantContext.Scope ignored = TenantContext.useTenant(tenantId)) {
            List<SysTenantSetting> rows = settingMapper.selectList(
                    new LambdaQueryWrapper<SysTenantSetting>()
                            .in(SysTenantSetting::getSettingKey, SUPPORTED_KEYS));
            Map<String, Boolean> values = rows.stream()
                    .filter(row -> row.getSettingKey() != null)
                    .collect(Collectors.toMap(
                            SysTenantSetting::getSettingKey,
                            row -> Boolean.TRUE.equals(row.getSettingValue()),
                            (first, ignoredValue) -> first));
            return new TenantIntegrationSettings(
                    values.getOrDefault(EXTERNAL_INBOUND_ENABLED, true),
                    values.getOrDefault(EXTERNAL_CLOSE_CALLBACK_ENABLED, true),
                    values.getOrDefault(DING_TALK_PUSH_ENABLED, true));
        }
    }

    @Transactional
    public TenantIntegrationSettings update(Long tenantId, TenantIntegrationSettingsRequest request) {
        requireBusinessTenant(tenantId);
        tenantLifecycleGuard.lockWritableTenant(tenantId);
        validateFullRequest(request);
        try (TenantContext.Scope ignored = TenantContext.useTenant(tenantId)) {
            settingMapper.upsert(tenantId, EXTERNAL_INBOUND_ENABLED,
                    request.getExternalInboundEnabled());
            settingMapper.upsert(tenantId, EXTERNAL_CLOSE_CALLBACK_ENABLED,
                    request.getExternalCloseCallbackEnabled());
            settingMapper.upsert(tenantId, DING_TALK_PUSH_ENABLED,
                    request.getDingTalkPushEnabled());
        }
        return new TenantIntegrationSettings(
                request.getExternalInboundEnabled(),
                request.getExternalCloseCallbackEnabled(),
                request.getDingTalkPushEnabled());
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

    private void validateFullRequest(TenantIntegrationSettingsRequest request) {
        if (request == null
                || request.getExternalInboundEnabled() == null
                || request.getExternalCloseCallbackEnabled() == null
                || request.getDingTalkPushEnabled() == null) {
            throw new WorkOrderException("三个外部集成开关均为必填布尔值");
        }
    }

    private void requireBusinessTenant(Long tenantId) {
        if (tenantId == null || tenantId <= 0) {
            throw new WorkOrderException("租户 ID 必须为正整数");
        }
    }
}
