package top.hetao.shiyuanticketmp.auth.controller.dto;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

import java.util.List;

@Data
public class AssignPermissionsRequest {
    @JsonProperty("permission_ids")
    @JsonAlias("permissionIds")
    private List<Long> permissionIds;
}
