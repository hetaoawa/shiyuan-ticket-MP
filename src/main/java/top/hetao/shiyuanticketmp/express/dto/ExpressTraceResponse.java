package top.hetao.shiyuanticketmp.express.dto;

import lombok.Data;

import java.util.List;

/**
 * 物流轨迹查询响应体。
 */
@Data
public class ExpressTraceResponse {

    /** 快递公司编码 */
    private String cpCode;

    /** 快递公司名称 */
    private String cpName;

    /** 物流单号 */
    private String trackingNo;

    /** 物流状态：0=查询出错，1=暂无记录，2=在途中，3=已签收，4=问题件 */
    private Integer status;

    /** 物流状态描述 */
    private String statusDesc;

    /** 物流轨迹列表（按时间倒序） */
    private List<TraceNode> traces;

    /**
     * 物流轨迹节点。
     */
    @Data
    public static class TraceNode {

        /** 时间 */
        private String time;

        /** 描述 */
        private String description;

        /** 位置 */
        private String location;
    }
}
