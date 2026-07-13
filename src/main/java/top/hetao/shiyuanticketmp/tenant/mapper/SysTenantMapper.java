package top.hetao.shiyuanticketmp.tenant.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import top.hetao.shiyuanticketmp.tenant.entity.SysTenant;

@Mapper
public interface SysTenantMapper extends BaseMapper<SysTenant> {

    /**
     * Counts references that make a tenant unsafe to delete. Table names are fixed deliberately;
     * tenant identifiers remain bound parameters and never become SQL fragments.
     */
    @Select("""
            SELECT
                (SELECT COUNT(*) FROM sys_user WHERE tenant_id = #{tenantId})
              + (SELECT COUNT(*) FROM sys_role WHERE tenant_id = #{tenantId})
              + (SELECT COUNT(*) FROM work_order WHERE tenant_id = #{tenantId})
              + (SELECT COUNT(*) FROM sys_file WHERE tenant_id = #{tenantId})
              + (SELECT COUNT(*) FROM sys_menu WHERE tenant_id = #{tenantId})
              + (SELECT COUNT(*) FROM webhook_dead_letter WHERE tenant_id = #{tenantId})
              + (SELECT COUNT(*) FROM sys_audit_log WHERE tenant_id = #{tenantId})
              + (SELECT COUNT(*) FROM work_order_comment WHERE tenant_id = #{tenantId})
            """)
    long countReferences(@Param("tenantId") Long tenantId);
}
