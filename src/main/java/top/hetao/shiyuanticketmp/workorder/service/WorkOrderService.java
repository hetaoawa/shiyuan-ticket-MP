package top.hetao.shiyuanticketmp.workorder.service;

import com.baomidou.mybatisplus.core.metadata.IPage;
import top.hetao.shiyuanticketmp.workorder.entity.WorkOrder;
import top.hetao.shiyuanticketmp.workorder.enums.WorkOrderStatus;

import java.util.List;
import java.util.Map;

/**
 * 工单业务服务接口
 */
public interface WorkOrderService {

    /**
     * 创建工单，初始状态 PENDING。
     */
    WorkOrder create(WorkOrder workOrder);

    /**
     * 按 ID 查询工单（租户拦截器自动隔离）。
     *
     * @param workOrderId 工单主键
     * @return 工单实体
     * @throws top.hetao.shiyuanticketmp.workorder.exception.WorkOrderException 工单不存在时
     */
    WorkOrder getById(Long workOrderId);

    /**
     * 将工单派发给指定处理人，状态 PENDING → IN_PROGRESS。
     *
     * @param workOrderId 工单主键
     * @param assigneeId  处理人 ID
     */
    WorkOrder assign(Long workOrderId, Long assigneeId);

    /**
     * 关闭工单，状态 IN_PROGRESS → CLOSED。
     * 此操作为资金强时效事件，触发严格事务后 WebHook 投递。
     *
     * @param workOrderId 工单主键
     * @param resolution  处理结论
     */
    WorkOrder close(Long workOrderId, String resolution);

    /**
     * 驳回工单，状态 IN_PROGRESS → REJECTED（终态）。
     *
     * @param workOrderId 工单主键
     * @param reason      驳回原因
     */
    WorkOrder reject(Long workOrderId, String reason);

    /**
     * 分页查询工单列表（租户拦截器自动隔离）。
     *
     * @param page       页码（从 1 开始）
     * @param pageSize   每页条数
     * @param status     状态筛选（可选）
     * @param trackingNo 物流单号模糊搜索（可选）
     * @return 分页结果
     */
    IPage<WorkOrder> listPage(int page, int pageSize, WorkOrderStatus status, String trackingNo);

    /**
     * 批量派发工单。
     *
     * @param workOrderIds 工单ID列表（必须都是 PENDING 状态）
     * @param assigneeId   处理人ID
     * @return 派发成功的工单数量
     */
    int batchAssign(List<Long> workOrderIds, Long assigneeId);

    /**
     * 按条件查询工单列表（不分页，用于导出）。
     */
    List<WorkOrder> listForExport(WorkOrderStatus status, String trackingNo);

    /**
     * 被驳回工单重新提交，状态 REJECTED → PENDING。
     *
     * <p>允许提交人编辑工单信息（标题、描述、物流单号、目标地址、优先级）后重新提交。
     * 驳回原因和关闭时间会被清除。
     *
     * @param workOrderId 工单主键
     * @param updateData  更新后的工单信息
     * @return 更新后的工单
     * @throws WorkOrderException 工单不存在或当前状态不是 REJECTED 时
     */
    WorkOrder resubmit(Long workOrderId, WorkOrder updateData);

    /**
     * 系统管理员强制驳回工单，任意非 CLOSED 状态均可驳回。
     *
     * <p>绕过常规状态校验，仅 CLOSED 状态不可驳回（已完结工单不可逆）。
     *
     * @param workOrderId 工单主键
     * @param reason      驳回原因
     * @return 驳回后的工单
     * @throws WorkOrderException 工单不存在或当前状态为 CLOSED 时
     */
    WorkOrder forceReject(Long workOrderId, String reason);
}
