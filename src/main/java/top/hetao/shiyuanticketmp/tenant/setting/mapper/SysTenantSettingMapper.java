package top.hetao.shiyuanticketmp.tenant.setting.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import top.hetao.shiyuanticketmp.tenant.setting.entity.SysTenantSetting;

@Mapper
public interface SysTenantSettingMapper extends BaseMapper<SysTenantSetting> {

    /**
     * Atomically creates, updates, or resurrects one setting. The unique tenant/key index
     * serializes concurrent first writes without an application-level check-then-insert race.
     */
    @Insert("""
            INSERT INTO sys_tenant_setting
                (tenant_id, setting_key, setting_value, created_at, updated_at, deleted)
            VALUES
                (#{tenantId}, #{settingKey}, #{settingValue}, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 0)
            ON DUPLICATE KEY UPDATE
                setting_value = VALUES(setting_value),
                updated_at = CURRENT_TIMESTAMP,
                deleted = 0
            """)
    int upsert(@Param("tenantId") Long tenantId,
               @Param("settingKey") String settingKey,
               @Param("settingValue") Boolean settingValue);
}
