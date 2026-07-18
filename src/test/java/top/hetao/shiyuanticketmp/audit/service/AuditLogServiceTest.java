package top.hetao.shiyuanticketmp.audit.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import top.hetao.shiyuanticketmp.audit.entity.SysAuditLog;
import top.hetao.shiyuanticketmp.audit.mapper.SysAuditLogMapper;
import top.hetao.shiyuanticketmp.auth.entity.SysUser;
import top.hetao.shiyuanticketmp.auth.mapper.SysUserMapper;
import top.hetao.shiyuanticketmp.common.context.TenantContext;
import top.hetao.shiyuanticketmp.tenant.service.TenantLifecycleGuard;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AuditLogServiceTest {

    @Test
    void enrichesHistoricalGlobalAdministratorAndLegacySystemOperators() {
        SysUserMapper userMapper = mock(SysUserMapper.class);
        when(userMapper.selectBatchIds(anyCollection())).thenReturn(List.of());
        SysUser platformAdmin = new SysUser();
        platformAdmin.setId(7L);
        platformAdmin.setTenantId(0L);
        platformAdmin.setNickname("平台管理员");
        when(userMapper.selectByIdIgnoreTenant(7L)).thenReturn(platformAdmin);
        AuditLogService service = new AuditLogService(
                mock(TenantLifecycleGuard.class), userMapper, new ObjectMapper());

        SysAuditLog manual = new SysAuditLog();
        manual.setAction("CLOSE");
        manual.setOperatorId(7L);
        manual.setDetail("""
                {"previousStatus":"IN_PROGRESS","currentStatus":"CLOSED","resolution":"已处理"}
                """);
        SysAuditLog legacySystem = new SysAuditLog();
        legacySystem.setAction("ASSIGN");
        legacySystem.setOperatorId(0L);
        legacySystem.setDetail("{\"autoAssignment\":true}");

        try (TenantContext.Scope ignored = TenantContext.useTenant(9L)) {
            service.enrichRecords(List.of(manual, legacySystem));
        }

        assertThat(manual.getOperatorName()).isEqualTo("平台管理员");
        assertThat(manual.getActionLabel()).isEqualTo("关闭工单");
        assertThat(manual.getDetailSummary()).contains("处理结论：已处理");
        assertThat(legacySystem.getOperatorName()).isEqualTo("系统");
        assertThat(legacySystem.getRequestSource()).isEqualTo("SYSTEM");
    }

    @Test
    void timelineKeepsLatestDatabaseWindowAndReturnsItInAscendingOrder() {
        SysUserMapper userMapper = mock(SysUserMapper.class);
        AuditLogService service = new AuditLogService(
                mock(TenantLifecycleGuard.class), userMapper, new ObjectMapper());
        SysAuditLogMapper auditMapper = mock(SysAuditLogMapper.class);
        org.springframework.test.util.ReflectionTestUtils.setField(service, "baseMapper", auditMapper);

        SysAuditLog newest = new SysAuditLog();
        newest.setId(502L);
        newest.setAction("CLOSE");
        newest.setDetail("{}");
        SysAuditLog older = new SysAuditLog();
        older.setId(501L);
        older.setAction("ASSIGN");
        older.setDetail("{}");
        when(auditMapper.selectCount(any())).thenReturn(502L);
        // Mapper 按 DESC 返回最新窗口；服务层负责反转为时间正序。
        when(auditMapper.selectList(any())).thenReturn(List.of(newest, older));

        AuditLogService.TimelineResult result = service.getWorkOrderTimeline(100L);

        assertThat(result.total()).isEqualTo(502L);
        assertThat(result.truncated()).isTrue();
        assertThat(result.records()).extracting(SysAuditLog::getId)
                .containsExactly(501L, 502L);
    }
}
