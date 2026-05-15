package top.hetao.shiyuanticketmp.express.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

import java.util.List;

/**
 * 物流轨迹查询响应体（映射外部接口 data 字段）。
 */
@Data
public class ExpressTraceResponse {

    /** 快递公司编码 */
    private String cpCode;

    /** 物流单号 */
    @JsonProperty("mailNo")
    private String mailNo;

    /** 快递公司名称 */
    @JsonProperty("logisticsCompanyName")
    private String logisticsCompanyName;

    /** 物流状态码（ACCEPT/TRANSPORT/DELIVERING/DELIVERED 等） */
    @JsonProperty("logisticsStatus")
    private String logisticsStatus;

    /** 物流状态描述（中文） */
    @JsonProperty("logisticsStatusDesc")
    private String logisticsStatusDesc;

    /** 最新物流时间 */
    @JsonProperty("theLastTime")
    private String theLastTime;

    /** 最新物流消息 */
    @JsonProperty("theLastMessage")
    private String theLastMessage;

    /** 订单号 */
    @JsonProperty("orderNo")
    private String orderNo;

    /** 快递员手机号 */
    @JsonProperty("cpMobile")
    private String cpMobile;

    /** 快递公司链接 */
    @JsonProperty("cpUrl")
    private String cpUrl;

    /** 物流轨迹详情列表 */
    @JsonProperty("logisticsTraceDetailList")
    private List<TraceNode> traces;

    /**
     * 物流轨迹节点。
     */
    @Data
    public static class TraceNode {

        /** 地区名称 */
        @JsonProperty("areaName")
        private String areaName;

        /** 时间描述（格式：yyyy-MM-dd HH:mm:ss） */
        @JsonProperty("timeDesc")
        private String timeDesc;

        /** 时间戳（毫秒） */
        @JsonProperty("time")
        private Long time;

        /** 物流状态（ACCEPT/TRANSPORT/DELIVERING/DELIVERED 等） */
        @JsonProperty("logisticsStatus")
        private String logisticsStatus;

        /** 物流子状态 */
        @JsonProperty("subLogisticsStatus")
        private String subLogisticsStatus;

        /** 物流描述 */
        @JsonProperty("desc")
        private String description;
    }
}
