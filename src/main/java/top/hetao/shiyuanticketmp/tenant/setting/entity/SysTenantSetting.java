package top.hetao.shiyuanticketmp.tenant.setting.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;
import top.hetao.shiyuanticketmp.common.entity.BaseEntity;

/** Tenant-scoped external integration setting. */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("sys_tenant_setting")
public class SysTenantSetting extends BaseEntity {

    private String settingKey;

    private Boolean settingValue;
}
