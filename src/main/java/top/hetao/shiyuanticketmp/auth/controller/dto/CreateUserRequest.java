package top.hetao.shiyuanticketmp.auth.controller.dto;

import com.fasterxml.jackson.annotation.JsonAlias;
import lombok.Data;

import java.util.List;

@Data
public class CreateUserRequest {
    private String username;
    private String password;
    private String nickname;
    private String phone;
    private String email;
    private String externalUserId;
    private Long tenantId;
    @JsonAlias({"role_ids"})
    private List<Long> roleIds;
}
