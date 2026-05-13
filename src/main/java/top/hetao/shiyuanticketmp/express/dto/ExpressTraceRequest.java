package top.hetao.shiyuanticketmp.express.dto;

import lombok.Data;

/**
 * 物流轨迹查询请求体。
 */
@Data
public class ExpressTraceRequest {

    /** 物流单号（必填） */
    private String trackingNo;

    /** 收件手机号后四位（顺丰、中通必填） */
    private String mobileLast4;

    /** 快递公司编码（可选，不传则自动识别） */
    private String cpCode;
}
