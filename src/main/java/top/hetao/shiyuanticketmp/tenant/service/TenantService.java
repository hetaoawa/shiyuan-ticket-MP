package top.hetao.shiyuanticketmp.tenant.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import top.hetao.shiyuanticketmp.common.context.TenantContext;
import top.hetao.shiyuanticketmp.auth.controller.dto.TenantOptionResponse;
import top.hetao.shiyuanticketmp.auth.service.RoleProvisioningService;
import top.hetao.shiyuanticketmp.tenant.controller.dto.TenantRequest;
import top.hetao.shiyuanticketmp.tenant.entity.SysTenant;
import top.hetao.shiyuanticketmp.tenant.mapper.SysTenantMapper;
import top.hetao.shiyuanticketmp.workorder.exception.WorkOrderException;

import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

@Service
public class TenantService {

    private static final Pattern CODE_PATTERN = Pattern.compile("[a-z0-9][a-z0-9_-]{1,63}");

    private final SysTenantMapper tenantMapper;
    private final RoleProvisioningService roleProvisioningService;
    private final TenantMenuProvisioningService menuProvisioningService;

    public TenantService(SysTenantMapper tenantMapper,
                         RoleProvisioningService roleProvisioningService,
                         TenantMenuProvisioningService menuProvisioningService) {
        this.tenantMapper = tenantMapper;
        this.roleProvisioningService = roleProvisioningService;
        this.menuProvisioningService = menuProvisioningService;
    }

    @Transactional(readOnly = true)
    public SysTenant requireEnabled(Long tenantId) {
        if (tenantId == null || tenantId < 0) {
            throw new WorkOrderException("租户 ID 非法");
        }
        SysTenant tenant = tenantMapper.selectById(tenantId);
        if (tenant == null || !Integer.valueOf(1).equals(tenant.getStatus())) {
            throw new WorkOrderException("租户不存在或已停用: " + tenantId);
        }
        return tenant;
    }

    @Transactional(readOnly = true)
    public List<SysTenant> listEnabled() {
        return tenantMapper.selectList(new LambdaQueryWrapper<SysTenant>()
                .eq(SysTenant::getStatus, 1)
                .ne(SysTenant::getId, 0L)
                .orderByAsc(SysTenant::getTenantName));
    }

    @Transactional(readOnly = true)
    public List<TenantOptionResponse> listLoginOptions() {
        Comparator<TenantOptionResponse> loginOrder = Comparator
                .comparingInt((TenantOptionResponse option) ->
                        "platform".equals(option.tenantCode()) ? 0 : 1)
                .thenComparing(TenantOptionResponse::tenantName)
                .thenComparing(TenantOptionResponse::tenantCode);
        return tenantMapper.selectList(new LambdaQueryWrapper<SysTenant>()
                        .eq(SysTenant::getStatus, 1))
                .stream()
                .filter(tenant -> Integer.valueOf(1).equals(tenant.getStatus()))
                .filter(tenant -> Integer.valueOf(0).equals(tenant.getDeleted()))
                .map(tenant -> new TenantOptionResponse(
                        tenant.getTenantCode(), tenant.getTenantName()))
                .sorted(loginOrder)
                .toList();
    }

    @Transactional(readOnly = true)
    public List<SysTenant> listAll() {
        return tenantMapper.selectList(new LambdaQueryWrapper<SysTenant>()
                .orderByAsc(SysTenant::getTenantName));
    }

    @Transactional(readOnly = true)
    public String getTenantName(Long tenantId) {
        if (tenantId == null || tenantId < 0) {
            return null;
        }
        SysTenant tenant = tenantMapper.selectById(tenantId);
        return tenant == null ? null : tenant.getTenantName();
    }

    @Transactional
    public SysTenant createTenant(TenantRequest request) {
        ValidatedTenant validated = validate(request);
        ensureCodeUnique(validated.code(), null);
        SysTenant tenant = new SysTenant();
        tenant.setTenantCode(validated.code());
        tenant.setTenantName(validated.name());
        // Provisioners use the normal enabled-tenant lifecycle gate. Insert enabled first so the
        // same transaction can initialize structure without a privileged bypass, then apply the
        // requested disabled state only after provisioning is complete.
        tenant.setStatus(1);
        tenantMapper.insert(tenant);
        roleProvisioningService.provisionTenantRoles(tenant.getId());
        menuProvisioningService.provisionTenantMenus(tenant.getId());
        if (validated.status() == 0) {
            tenant.setStatus(0);
            if (tenantMapper.updateById(tenant) != 1) {
                throw new WorkOrderException("租户初始化后停用失败: " + tenant.getId());
            }
        }
        return tenant;
    }

    @Transactional
    public SysTenant updateTenant(Long tenantId, TenantRequest request) {
        if (tenantId == null || tenantId < 0) {
            throw new WorkOrderException("租户 ID 非法");
        }
        SysTenant tenant = tenantMapper.selectByIdForUpdate(tenantId);
        if (tenant == null) {
            throw new WorkOrderException("租户不存在: " + tenantId);
        }
        ValidatedTenant validated = validate(request);
        if (tenantId == 0L && validated.status() != 1) {
            throw new WorkOrderException("平台租户不可停用");
        }
        String existingCode = tenant.getTenantCode() == null
                ? "" : tenant.getTenantCode().trim().toLowerCase(Locale.ROOT);
        if (!existingCode.equals(validated.code())) {
            throw new WorkOrderException("租户编码创建后不可修改");
        }
        tenant.setTenantName(validated.name());
        tenant.setStatus(validated.status());
        if (tenantMapper.updateById(tenant) != 1) {
            throw new WorkOrderException("租户已被并发修改或删除: " + tenantId);
        }
        return tenant;
    }

    @Transactional
    public void deleteTenant(Long tenantId) {
        if (tenantId == null || tenantId <= 0) {
            throw new WorkOrderException("平台租户不可删除");
        }
        if (tenantId.equals(TenantContext.getTenantId())) {
            throw new WorkOrderException("当前活动租户不可删除，请先切换至其他租户");
        }
        try (TenantContext.Scope ignored = TenantContext.useInternalBypass()) {
            SysTenant tenant = tenantMapper.selectByIdForUpdate(tenantId);
            if (tenant == null) {
                throw new WorkOrderException("租户不存在: " + tenantId);
            }
            if (!Integer.valueOf(0).equals(tenant.getStatus())) {
                throw new WorkOrderException("请先停用租户，再执行删除");
            }
            if (tenantMapper.countBusinessReferences(tenantId) > 0) {
                throw new WorkOrderException("租户仍有关联业务数据，请停用租户而不是删除");
            }
            tenantMapper.deleteTenantUserRoleRelations(tenantId);
            tenantMapper.deleteTenantRolePermissionRelations(tenantId);
            tenantMapper.deleteTenantRoles(tenantId);
            tenantMapper.deleteTenantMenus(tenantId);
            tenantMapper.deleteTenantSettings(tenantId);
            // Logical deletion intentionally retains tenant_code uniqueness.
            if (tenantMapper.deleteById(tenantId) != 1) {
                throw new WorkOrderException("租户已被并发修改或删除: " + tenantId);
            }
        }
    }

    private ValidatedTenant validate(TenantRequest request) {
        if (request == null) {
            throw new WorkOrderException("租户参数不能为空");
        }
        String code = request.getTenantCode() == null
                ? "" : request.getTenantCode().trim().toLowerCase(Locale.ROOT);
        if (!CODE_PATTERN.matcher(code).matches()) {
            throw new WorkOrderException("租户编码须为 2-64 位小写字母、数字、下划线或短横线");
        }
        String name = request.getTenantName() == null ? "" : request.getTenantName().trim();
        if (name.isEmpty() || name.length() > 100) {
            throw new WorkOrderException("租户名称须为 1-100 个字符");
        }
        Integer status = request.getStatus();
        if (status == null) {
            status = 1;
        }
        if (status != 0 && status != 1) {
            throw new WorkOrderException("租户状态只能为 0 或 1");
        }
        return new ValidatedTenant(code, name, status);
    }

    private void ensureCodeUnique(String code, Long excludingId) {
        LambdaQueryWrapper<SysTenant> query = new LambdaQueryWrapper<SysTenant>()
                .eq(SysTenant::getTenantCode, code);
        if (excludingId != null) {
            query.ne(SysTenant::getId, excludingId);
        }
        if (tenantMapper.selectCount(query) > 0) {
            throw new WorkOrderException("租户编码已存在: " + code);
        }
    }

    private record ValidatedTenant(String code, String name, Integer status) {
    }
}
