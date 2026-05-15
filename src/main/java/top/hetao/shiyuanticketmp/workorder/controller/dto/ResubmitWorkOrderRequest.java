package top.hetao.shiyuanticketmp.workorder.controller.dto;

import lombok.Data;

/**
 * 被驳回工单重新提交请求体。
 *
 * <p>所有字段均为可选，仅传入需要修改的字段。
 */
@Data
public class ResubmitWorkOrderRequest {

    /** 工单标题 */
    private String title;

    /** 工单描述 */
    private String description;

    /** 物流单号 */
    private String trackingNo;

    /** 目标地址 */
    private String targetAddress;

    /** 优先级（1=低 2=中 3=高） */
    private Integer priority;
}
