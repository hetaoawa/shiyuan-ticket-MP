package top.hetao.shiyuanticketmp.auth.controller.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

import java.util.List;

@Data
public class AssignPermissionsRequest {
    @JsonProperty("permission_ids")
    private List<Long> permissionIds;
}
