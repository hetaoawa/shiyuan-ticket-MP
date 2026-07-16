package top.hetao.shiyuanticketmp.workorder.controller.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonAnySetter;
import lombok.Data;

@Data
@JsonIgnoreProperties(ignoreUnknown = false)
public class BatchCreateWorkOrderItem {
    private String title;
    private String description;
    private String trackingNo;
    private String targetAddress;
    private String type;
    private Integer priority;

    @JsonAnySetter
    public void rejectUnknownField(String name, Object value) {
        throw new IllegalArgumentException("批量建单不接受字段: " + name);
    }
}
