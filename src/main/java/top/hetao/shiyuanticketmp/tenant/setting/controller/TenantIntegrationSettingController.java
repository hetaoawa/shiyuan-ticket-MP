package top.hetao.shiyuanticketmp.tenant.setting.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import top.hetao.shiyuanticketmp.common.context.TenantContext;
import top.hetao.shiyuanticketmp.tenant.setting.controller.dto.TenantIntegrationSettingsRequest;
import top.hetao.shiyuanticketmp.tenant.setting.service.TenantIntegrationSettingService;

import java.util.HashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/admin/settings/external-integrations")
public class TenantIntegrationSettingController {

    private final TenantIntegrationSettingService settingService;

    public TenantIntegrationSettingController(TenantIntegrationSettingService settingService) {
        this.settingService = settingService;
    }

    @GetMapping
    @SaCheckPermission("settings:view")
    public Map<String, Object> get() {
        Long tenantId = TenantContext.requireTenantId();
        return success(settingService.get(tenantId), "查询成功");
    }

    @PutMapping
    @SaCheckPermission("settings:update")
    public Map<String, Object> update(@RequestBody TenantIntegrationSettingsRequest request) {
        Long tenantId = TenantContext.requireTenantId();
        return success(settingService.update(tenantId, request), "设置已保存");
    }

    private Map<String, Object> success(Object data, String message) {
        Map<String, Object> response = new HashMap<>();
        response.put("code", 200);
        response.put("message", message);
        response.put("data", data);
        return response;
    }
}
