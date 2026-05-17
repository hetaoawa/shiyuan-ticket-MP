package top.hetao.shiyuanticketmp.workorder.controller.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

/**
 * 创建工单请求体。
 */
@Data
public class CreateWorkOrderRequest {

    /** 工单标题（必填） */
    private String title;

    /** 工单描述 */
    private String description;

    /** 物流单号 */
    private String trackingNo;

    /** 目标地址 */
    private String targetAddress;

    /** 工单类型（可选，不传则自动解析） */
    private String type;

    /** 优先级（1=低 2=中 3=高，默认 2） */
    private Integer priority;

    /** 外部发送人 ID（货主侧 senderStaffId，传入时必须映射到系统用户） */
    private String senderStaffId;

    /** 外部群 ID（货主侧 conversationId） */
    private String conversationId;
}
