package top.hetao.shiyuanticketmp.tenant.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import top.hetao.shiyuanticketmp.auth.controller.dto.TenantOptionResponse;
import top.hetao.shiyuanticketmp.auth.service.RoleProvisioningService;
import top.hetao.shiyuanticketmp.tenant.entity.SysTenant;
import top.hetao.shiyuanticketmp.tenant.mapper.SysTenantMapper;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TenantServiceLoginOptionsTest {

    @BeforeAll
    static void initializeMybatisTableMetadata() {
        TableInfoHelper.initTableInfo(
                new MapperBuilderAssistant(new MybatisConfiguration(), "test"), SysTenant.class);
    }

    @Mock
    private SysTenantMapper tenantMapper;

    @Mock
    private RoleProvisioningService roleProvisioningService;

    @Mock
    private TenantMenuProvisioningService menuProvisioningService;

    @Test
    void listLoginOptionsProjectsEnabledNonDeletedTenantsWithPlatformFirst() {
        when(tenantMapper.selectList(any())).thenReturn(List.of(
                tenant(3L, "zeta", "Acme", 1, 0),
                tenant(7L, "disabled", "Disabled", 0, 0),
                tenant(0L, "platform", "平台", 1, 0),
                tenant(2L, "alpha", "Acme", 1, 0),
                tenant(9L, "deleted", "Deleted", 1, 1)
        ));
        TenantService service = new TenantService(
                tenantMapper, roleProvisioningService, menuProvisioningService);

        List<TenantOptionResponse> options = service.listLoginOptions();

        assertEquals(List.of(
                new TenantOptionResponse("platform", "平台"),
                new TenantOptionResponse("alpha", "Acme"),
                new TenantOptionResponse("zeta", "Acme")
        ), options);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<LambdaQueryWrapper<SysTenant>> queryCaptor =
                ArgumentCaptor.forClass(LambdaQueryWrapper.class);
        verify(tenantMapper).selectList(queryCaptor.capture());
        LambdaQueryWrapper<SysTenant> query = queryCaptor.getValue();
        assertTrue(query.getSqlSegment().contains("status"));
        assertTrue(query.getParamNameValuePairs().containsValue(1));
    }

    private static SysTenant tenant(Long id,
                                    String tenantCode,
                                    String tenantName,
                                    Integer status,
                                    Integer deleted) {
        SysTenant tenant = new SysTenant();
        tenant.setId(id);
        tenant.setTenantCode(tenantCode);
        tenant.setTenantName(tenantName);
        tenant.setStatus(status);
        tenant.setCreatedAt(LocalDateTime.of(2026, 1, 1, 0, 0));
        tenant.setUpdatedAt(LocalDateTime.of(2026, 1, 2, 0, 0));
        tenant.setDeleted(deleted);
        return tenant;
    }
}
