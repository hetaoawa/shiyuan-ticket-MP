package top.hetao.shiyuanticketmp.tenant.controller.dto;

import lombok.Data;

@Data
public class TenantRequest {
    private String tenantCode;
    private String tenantName;
    private Integer status;
}
