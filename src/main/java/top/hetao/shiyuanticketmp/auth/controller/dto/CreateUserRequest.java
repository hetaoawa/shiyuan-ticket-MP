package top.hetao.shiyuanticketmp.auth.controller.dto;

import lombok.Data;

@Data
public class CreateUserRequest {
    private String username;
    private String password;
    private String nickname;
    private String phone;
    private String email;
}
