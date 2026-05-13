package top.hetao.shiyuanticketmp.auth.controller.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

@Data
public class CreateRoleRequest {
    @JsonProperty("role_code")
    private String roleCode;
    @JsonProperty("role_name")
    private String roleName;
}
