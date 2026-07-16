package top.hetao.shiyuanticketmp.auth.controller.dto;

import lombok.Data;

import java.util.List;

@Data
public class AssignTenantAdminsRequest {
    private List<Long> userIds;
}
