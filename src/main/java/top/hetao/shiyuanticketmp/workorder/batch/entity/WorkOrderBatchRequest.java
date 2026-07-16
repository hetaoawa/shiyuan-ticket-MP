package top.hetao.shiyuanticketmp.workorder.batch.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;
import top.hetao.shiyuanticketmp.common.entity.BaseEntity;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("work_order_batch_request")
public class WorkOrderBatchRequest extends BaseEntity {
    private Long requesterId;
    private String idempotencyKey;
    private String requestHash;
    private String workOrderIdsJson;
}
