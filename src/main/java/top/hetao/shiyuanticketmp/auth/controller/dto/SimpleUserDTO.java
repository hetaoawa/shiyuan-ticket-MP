package top.hetao.shiyuanticketmp.auth.controller.dto;

import lombok.Data;

/**
 * 简单用户信息 DTO，用于下拉框显示
 */
@Data
public class SimpleUserDTO {
    private Long id;
    private String username;
    private String nickname;
}