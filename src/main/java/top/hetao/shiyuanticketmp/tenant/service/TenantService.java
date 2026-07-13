package top.hetao.shiyuanticketmp.tenant.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import top.hetao.shiyuanticketmp.common.context.TenantContext;
import top.hetao.shiyuanticketmp.auth.service.RoleProvisioningService;
import top.hetao.shiyuanticketmp.tenant.controller.dto.TenantRequest;
import top.hetao.shiyuanticketmp.tenant.entity.SysTenant;
import top.hetao.shiyuanticketmp.tenant.mapper.SysTenantMapper;
import top.hetao.shiyuanticketmp.workorder.exception.WorkOrderException;

import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

@Service
public class TenantService {

    private static final Pattern CODE_PATTERN = Pattern.compile("[a-z0-9][a-z0-9_-]{1,63}");

    private final SysTenantMapper tenantMapper;
    private final RoleProvisioningService roleProvisioningService;

    public TenantService(SysTenantMapper tenantMapper, RoleProvisioningService roleProvisioningService) {
        this.tenantMapper = tenantMapper;
        this.roleProvisioningService = roleProvisioningService;
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
        tenant.setStatus(validated.status());
        tenantMapper.insert(tenant);
        roleProvisioningService.provisionTenantRoles(tenant.getId());
        return tenant;
    }

    @Transactional
    public SysTenant updateTenant(Long tenantId, TenantRequest request) {
        if (tenantId == null || tenantId < 0) {
            throw new WorkOrderException("租户 ID 非法");
        }
        SysTenant tenant = tenantMapper.selectById(tenantId);
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
        tenantMapper.updateById(tenant);
        return tenant;
    }

    @Transactional
    public void deleteTenant(Long tenantId) {
        if (tenantId == null || tenantId <= 0) {
            throw new WorkOrderException("平台租户不可删除");
        }
        if (tenantMapper.selectById(tenantId) == null) {
            throw new WorkOrderException("租户不存在: " + tenantId);
        }
        long referenceCount;
        // 引用统计跨越所有租户表，需要最小范围内部旁路；SQL 中的表名固定且 ID 参数化。
        try (TenantContext.Scope ignored = TenantContext.useInternalBypass()) {
            referenceCount = tenantMapper.countReferences(tenantId);
        }
        if (referenceCount > 0) {
            throw new WorkOrderException("租户仍有关联业务数据，请停用租户而不是删除");
        }
        // 逻辑删除后仍保留唯一 tenant_code；编码是永久稳定标识，不允许回收复用。
        tenantMapper.deleteById(tenantId);
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
