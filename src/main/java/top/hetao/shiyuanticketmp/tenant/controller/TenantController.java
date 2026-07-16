package top.hetao.shiyuanticketmp.tenant.controller;

import cn.dev33.satoken.annotation.SaCheckLogin;
import cn.dev33.satoken.annotation.SaCheckRole;
import cn.dev33.satoken.stp.StpUtil;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import top.hetao.shiyuanticketmp.common.context.TenantContext;
import top.hetao.shiyuanticketmp.auth.controller.dto.AssignTenantAdminsRequest;
import top.hetao.shiyuanticketmp.auth.service.UserService;
import top.hetao.shiyuanticketmp.tenant.controller.dto.TenantRequest;
import top.hetao.shiyuanticketmp.tenant.entity.SysTenant;
import top.hetao.shiyuanticketmp.tenant.service.TenantService;
import top.hetao.shiyuanticketmp.workorder.exception.WorkOrderException;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/admin/tenants")
public class TenantController {

    private final TenantService tenantService;
    private final UserService userService;

    public TenantController(TenantService tenantService, UserService userService) {
        this.tenantService = tenantService;
        this.userService = userService;
    }

    @SaCheckLogin
    @GetMapping("/options")
    public Map<String, Object> options() {
        if (StpUtil.hasRole("GLOBAL_SYSTEM_ADMIN")) {
            return success(tenantService.listEnabled());
        }
        Long activeTenantId = TenantContext.requireTenantId();
        return success(List.of(tenantService.requireEnabled(activeTenantId)));
    }

    @SaCheckRole("GLOBAL_SYSTEM_ADMIN")
    @GetMapping
    public Map<String, Object> list() {
        return success(tenantService.listAll());
    }

    @SaCheckRole("GLOBAL_SYSTEM_ADMIN")
    @PostMapping
    public Map<String, Object> create(@RequestBody TenantRequest request) {
        Map<String, Object> response = success(tenantService.createTenant(request));
        response.put("message", "租户创建成功");
        return response;
    }

    @SaCheckRole("GLOBAL_SYSTEM_ADMIN")
    @PutMapping("/{id}")
    public Map<String, Object> update(@PathVariable Long id, @RequestBody TenantRequest request) {
        Map<String, Object> response = success(tenantService.updateTenant(id, request));
        response.put("message", "租户更新成功");
        return response;
    }

    @SaCheckRole("GLOBAL_SYSTEM_ADMIN")
    @DeleteMapping("/{id}")
    public Map<String, Object> delete(@PathVariable Long id) {
        tenantService.deleteTenant(id);
        Map<String, Object> response = new HashMap<>();
        response.put("code", 200);
        response.put("message", "租户删除成功");
        return response;
    }

    @SaCheckLogin
    @GetMapping("/{tenantId}/system-admins")
    public Map<String, Object> getSystemAdmins(@PathVariable Long tenantId) {
        requireCurrentTenantAdministrator(tenantId);
        return success(userService.listTenantSystemAdmins(tenantId));
    }

    @SaCheckLogin
    @PutMapping("/{tenantId}/system-admins")
    public Map<String, Object> replaceSystemAdmins(@PathVariable Long tenantId,
                                                   @RequestBody AssignTenantAdminsRequest request) {
        requireCurrentTenantAdministrator(tenantId);
        userService.replaceTenantSystemAdmins(tenantId, request == null ? null : request.getUserIds());
        Map<String, Object> response = success(userService.listTenantSystemAdmins(tenantId));
        response.put("message", "租户系统管理员设置成功");
        return response;
    }

    private void requireCurrentTenantAdministrator(Long tenantId) {
        Long activeTenantId = TenantContext.requireTenantId();
        if (tenantId == null || tenantId <= 0 || !tenantId.equals(activeTenantId)) {
            throw new WorkOrderException("只能管理当前活动租户的系统管理员");
        }
        if (!StpUtil.hasRole("GLOBAL_SYSTEM_ADMIN") && !StpUtil.hasRole("SYSTEM_ADMIN")) {
            throw new WorkOrderException("无权设置租户系统管理员");
        }
    }

    private Map<String, Object> success(Object data) {
        Map<String, Object> response = new HashMap<>();
        response.put("code", 200);
        response.put("data", data);
        return response;
    }
}
