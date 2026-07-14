package top.hetao.shiyuanticketmp.workorder.controller.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonAnySetter;
import lombok.Data;

import java.util.List;

@Data
@JsonIgnoreProperties(ignoreUnknown = false)
public class BatchCreateWorkOrderRequest {
    private List<BatchCreateWorkOrderItem> items;

    @JsonAnySetter
    public void rejectUnknownField(String name, Object value) {
        throw new IllegalArgumentException("批量建单不接受字段: " + name);
    }
}
