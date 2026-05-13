package top.hetao.shiyuanticketmp.auth.controller.dto;

import lombok.Data;

import java.util.List;

@Data
public class AssignRolesRequest {
    private List<Long> roleIds;
}
