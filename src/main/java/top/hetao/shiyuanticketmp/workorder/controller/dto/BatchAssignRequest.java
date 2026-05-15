package top.hetao.shiyuanticketmp.workorder.controller.dto;

import lombok.Data;

import java.util.List;

/**
 * 批量派发请求体。
 */
@Data
public class BatchAssignRequest {

    /** 工单ID列表（必须都是 PENDING 状态） */
    private List<Long> workOrderIds;

    /** 处理人ID */
    private Long assigneeId;
}
