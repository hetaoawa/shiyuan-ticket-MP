package top.hetao.shiyuanticketmp.tenant.setting.controller.dto;

import lombok.Data;

/** Full replacement payload. Wrapper Booleans distinguish false from a missing field. */
@Data
public class TenantIntegrationSettingsRequest {

    private Boolean externalInboundEnabled;
    private Boolean externalCloseCallbackEnabled;
    private Boolean dingTalkPushEnabled;
}
