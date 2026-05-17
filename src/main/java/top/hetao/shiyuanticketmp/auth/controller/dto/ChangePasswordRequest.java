package top.hetao.shiyuanticketmp.auth.controller.dto;

import lombok.Data;

@Data
public class ChangePasswordRequest {
    private String oldPassword;
    private String newPassword;
}
