package top.hetao.shiyuanticketmp.auth;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import top.hetao.shiyuanticketmp.auth.controller.dto.TenantOptionResponse;
import top.hetao.shiyuanticketmp.tenant.service.TenantService;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/auth")
public class AuthTenantOptionController {

    private final TenantService tenantService;

    public AuthTenantOptionController(TenantService tenantService) {
        this.tenantService = tenantService;
    }

    @GetMapping("/tenant-options")
    public Map<String, Object> tenantOptions() {
        List<TenantOptionResponse> options = tenantService.listLoginOptions();
        Map<String, Object> result = new HashMap<>();
        result.put("code", 200);
        result.put("message", "获取租户选项成功");
        result.put("data", options);
        return result;
    }
}
