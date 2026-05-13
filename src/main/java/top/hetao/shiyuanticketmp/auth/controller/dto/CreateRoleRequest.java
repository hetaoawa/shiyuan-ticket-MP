package top.hetao.shiyuanticketmp.auth.controller.dto;

import lombok.Data;

@Data
public class CreateRoleRequest {
    private String roleCode;
    private String roleName;
}
