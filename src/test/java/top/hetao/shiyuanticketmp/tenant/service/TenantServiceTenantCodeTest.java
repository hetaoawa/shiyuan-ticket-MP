package top.hetao.shiyuanticketmp.tenant.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import top.hetao.shiyuanticketmp.auth.service.RoleProvisioningService;
import top.hetao.shiyuanticketmp.tenant.entity.SysTenant;
import top.hetao.shiyuanticketmp.tenant.mapper.SysTenantMapper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TenantServiceTenantCodeTest {

    @Mock
    private SysTenantMapper tenantMapper;

    @Mock
    private RoleProvisioningService roleProvisioningService;

    @Mock
    private TenantMenuProvisioningService menuProvisioningService;

    @Test
    void returnsCodeForPlatformAndDisabledNonDeletedTenants() {
        when(tenantMapper.selectById(0L)).thenReturn(tenant(0L, "platform", 1, 0));
        when(tenantMapper.selectById(100L)).thenReturn(tenant(100L, "tenant-100", 0, 0));
        TenantService service = service();

        assertEquals("platform", service.getTenantCode(0L));
        assertEquals("tenant-100", service.getTenantCode(100L));
    }

    @Test
    void returnsNullWithoutLookupForNullOrNegativeTenantId() {
        TenantService service = service();

        assertNull(service.getTenantCode(null));
        assertNull(service.getTenantCode(-1L));
        verify(tenantMapper, never()).selectById(org.mockito.ArgumentMatchers.anyLong());
    }

    @Test
    void returnsNullForMissingDeletedOrBlankCodeTenant() {
        when(tenantMapper.selectById(101L)).thenReturn(null);
        when(tenantMapper.selectById(102L)).thenReturn(tenant(102L, "deleted", 1, 1));
        when(tenantMapper.selectById(103L)).thenReturn(tenant(103L, "  ", 1, 0));
        TenantService service = service();

        assertNull(service.getTenantCode(101L));
        assertNull(service.getTenantCode(102L));
        assertNull(service.getTenantCode(103L));
    }

    private TenantService service() {
        return new TenantService(tenantMapper, roleProvisioningService, menuProvisioningService);
    }

    private static SysTenant tenant(Long id, String code, Integer status, Integer deleted) {
        SysTenant tenant = new SysTenant();
        tenant.setId(id);
        tenant.setTenantCode(code);
        tenant.setStatus(status);
        tenant.setDeleted(deleted);
        return tenant;
    }
}
