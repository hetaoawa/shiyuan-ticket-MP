package top.hetao.shiyuanticketmp.workorder.controller.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

import java.util.List;

/**
 * 批量派发请求体。
 */
@Data
public class BatchAssignRequest {

    /** 工单ID列表（必须都是 PENDING 状态） */
    @JsonProperty("work_order_ids")
    private List<Long> workOrderIds;

    /** 处理人ID */
    @JsonProperty("assignee_id")
    private Long assigneeId;
}
