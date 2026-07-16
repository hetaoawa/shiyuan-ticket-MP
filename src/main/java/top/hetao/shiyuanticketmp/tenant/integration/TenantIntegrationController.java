package top.hetao.shiyuanticketmp.tenant.integration;

import cn.dev33.satoken.annotation.SaCheckPermission;
import cn.dev33.satoken.annotation.SaCheckRole;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.web.bind.annotation.*;
import top.hetao.shiyuanticketmp.common.context.TenantContext;

import java.util.LinkedHashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/admin/settings/integrations")
public class TenantIntegrationController {
    private final TenantIntegrationService service;
    private final LegacyIntegrationImporter legacyImporter;
    public TenantIntegrationController(TenantIntegrationService service, LegacyIntegrationImporter legacyImporter) {
        this.service=service; this.legacyImporter=legacyImporter;
    }

    @GetMapping
    @SaCheckPermission("settings:view")
    public Map<String,Object> list(){ return ok(service.list(TenantContext.requireTenantId())); }

    @GetMapping("/{type}")
    @SaCheckPermission("settings:view")
    public Map<String,Object> get(@PathVariable String type){
        return ok(service.get(TenantContext.requireTenantId(),IntegrationType.parse(type)));
    }

    @PutMapping("/{type}")
    @SaCheckPermission("settings:update")
    public Map<String,Object> update(@PathVariable String type,@RequestBody JsonNode request){
        return ok(service.update(TenantContext.requireTenantId(),IntegrationType.parse(type),request));
    }
    @PostMapping("/legacy-import")
    @SaCheckRole("GLOBAL_SYSTEM_ADMIN")
    public Map<String,Object> importLegacy(){return ok(legacyImporter.importOnce());}
    private Map<String,Object> ok(Object data){Map<String,Object> map=new LinkedHashMap<>();map.put("code",200);map.put("message","OK");map.put("data",data);return map;}
}
