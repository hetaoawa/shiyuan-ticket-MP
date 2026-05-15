package top.hetao.shiyuanticketmp.express.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;
import top.hetao.shiyuanticketmp.common.entity.BaseEntity;

import java.time.LocalDateTime;

/**
 * 物流轨迹落库实体，对应数据库表 {@code express_trace}。
 *
 * <p>按物流单号唯一存储，支持 12 小时有效期（已签收永不过期）。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("express_trace")
public class ExpressTraceRecord extends BaseEntity {

    /** 物流单号（唯一索引） */
    private String trackingNo;

    /** 快递公司编码 */
    private String cpCode;

    /** 物流状态码（ACCEPT/TRANSPORT/DELIVERING/DELIVERED） */
    private String logisticsStatus;

    /** 物流状态描述 */
    private String logisticsStatusDesc;

    /** 完整物流响应 JSON */
    @TableField("response_json")
    private String responseJson;

    /** 获取时间 */
    private LocalDateTime fetchedAt;

    /** 过期时间（已签收为 NULL，表示永不过期） */
    private LocalDateTime expiresAt;
}
