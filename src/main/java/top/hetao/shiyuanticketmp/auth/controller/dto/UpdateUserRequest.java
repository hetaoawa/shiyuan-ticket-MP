package top.hetao.shiyuanticketmp.auth.controller.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

@Data
public class UpdateUserRequest {
    private String nickname;
    private String phone;
    private String email;
    private Integer status;
    private String externalUserId;
}
