package top.hetao.shiyuanticketmp.tenant.integration;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;
import top.hetao.shiyuanticketmp.common.entity.BaseEntity;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("sys_tenant_integration")
public class SysTenantIntegration extends BaseEntity {
    @TableField("integration_type") private String integrationType;
    private Boolean enabled;
    @TableField("public_config_json") private String publicConfigJson;
    @TableField("secret_config_json") private String secretConfigJson;
    @TableField("config_version") private Long configVersion;
}
