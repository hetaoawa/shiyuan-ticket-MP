package top.hetao.shiyuanticketmp.tenant.integration;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface TenantIntegrationMapper extends BaseMapper<SysTenantIntegration> {
    @Update("UPDATE sys_tenant_integration SET enabled=#{enabled}, public_config_json=#{publicJson}, " +
            "secret_config_json=#{secretJson}, config_version=config_version+1, updated_at=NOW() " +
            "WHERE id=#{id} AND tenant_id=#{tenantId} AND config_version=#{expectedVersion} AND deleted=0")
    int updateCas(@Param("id") Long id, @Param("tenantId") Long tenantId,
                  @Param("expectedVersion") Long expectedVersion, @Param("enabled") boolean enabled,
                  @Param("publicJson") String publicJson, @Param("secretJson") String secretJson);
}
