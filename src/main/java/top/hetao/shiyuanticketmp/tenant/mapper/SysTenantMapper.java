package top.hetao.shiyuanticketmp.tenant.mapper;

import com.baomidou.mybatisplus.annotation.InterceptorIgnore;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import top.hetao.shiyuanticketmp.tenant.entity.SysTenant;

@Mapper
public interface SysTenantMapper extends BaseMapper<SysTenant> {

    /** Exclusive current locking read used by tenant update/delete until transaction completion. */
    @Select("""
            SELECT id, tenant_code, tenant_name, status, created_at, updated_at, deleted
            FROM sys_tenant
            WHERE id = #{tenantId} AND deleted = 0
            FOR UPDATE
            """)
    SysTenant selectByIdForUpdate(@Param("tenantId") Long tenantId);

    /**
     * Shared current locking read for interactive/business writes. Disabled and deleted tenants
     * are rejected. The lock is held until the caller transaction completes.
     */
    @Select("""
            SELECT id, tenant_code, tenant_name, status, created_at, updated_at, deleted
            FROM sys_tenant
            WHERE id = #{tenantId} AND status = 1 AND deleted = 0
            LOCK IN SHARE MODE
            """)
    @InterceptorIgnore(tenantLine = "true", dataPermission = "false")
    SysTenant selectEnabledByIdForShare(@Param("tenantId") Long tenantId);

    /**
     * Shared current locking read for writes derived from an already committed event. Disabled
     * tenants remain eligible so their audit/dead-letter trail is not lost; deleted tenants are
     * rejected. The lock is held until the caller transaction completes.
     */
    @Select("""
            SELECT id, tenant_code, tenant_name, status, created_at, updated_at, deleted
            FROM sys_tenant
            WHERE id = #{tenantId} AND deleted = 0
            LOCK IN SHARE MODE
            """)
    @InterceptorIgnore(tenantLine = "true", dataPermission = "false")
    SysTenant selectNotDeletedByIdForShare(@Param("tenantId") Long tenantId);

    /**
     * Counts durable business records only. Provisioned roles, menus, settings and relation
     * rows are tenant structure and are removed after this check succeeds.
     */
    @Select("""
            SELECT
                (SELECT COUNT(*) FROM sys_user WHERE tenant_id = #{tenantId})
              + (SELECT COUNT(*) FROM work_order WHERE tenant_id = #{tenantId})
              + (SELECT COUNT(*) FROM sys_file WHERE tenant_id = #{tenantId})
              + (SELECT COUNT(*) FROM webhook_dead_letter WHERE tenant_id = #{tenantId})
              + (SELECT COUNT(*) FROM sys_audit_log WHERE tenant_id = #{tenantId})
              + (SELECT COUNT(*) FROM work_order_comment WHERE tenant_id = #{tenantId})
            """)
    long countBusinessReferences(@Param("tenantId") Long tenantId);

    @Delete("""
            DELETE user_role
            FROM sys_user_role user_role
            JOIN sys_role role_record ON role_record.id = user_role.role_id
            WHERE role_record.tenant_id = #{tenantId}
            """)
    int deleteTenantUserRoleRelations(@Param("tenantId") Long tenantId);

    @Delete("""
            DELETE role_permission
            FROM sys_role_permission role_permission
            JOIN sys_role role_record ON role_record.id = role_permission.role_id
            WHERE role_record.tenant_id = #{tenantId}
            """)
    int deleteTenantRolePermissionRelations(@Param("tenantId") Long tenantId);

    @Delete("DELETE FROM sys_role WHERE tenant_id = #{tenantId}")
    int deleteTenantRoles(@Param("tenantId") Long tenantId);

    @Delete("DELETE FROM sys_menu WHERE tenant_id = #{tenantId}")
    int deleteTenantMenus(@Param("tenantId") Long tenantId);

    @Delete("DELETE FROM sys_tenant_setting WHERE tenant_id = #{tenantId}")
    int deleteTenantSettings(@Param("tenantId") Long tenantId);
}
