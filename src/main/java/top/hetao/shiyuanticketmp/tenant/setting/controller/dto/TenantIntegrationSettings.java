package top.hetao.shiyuanticketmp.tenant.setting.controller.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class TenantIntegrationSettings {

    private Boolean externalInboundEnabled;
    private Boolean externalCloseCallbackEnabled;
    private Boolean dingTalkPushEnabled;
}
