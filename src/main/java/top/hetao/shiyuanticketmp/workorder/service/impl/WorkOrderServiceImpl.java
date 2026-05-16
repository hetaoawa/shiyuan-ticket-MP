package top.hetao.shiyuanticketmp.workorder.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.transaction.annotation.Transactional;
import top.hetao.shiyuanticketmp.auth.mapper.SysRoleMapper;
import top.hetao.shiyuanticketmp.workorder.cache.WorkOrderCacheManager;
import top.hetao.shiyuanticketmp.workorder.entity.WorkOrder;
import top.hetao.shiyuanticketmp.workorder.enums.WorkOrderStatus;
import top.hetao.shiyuanticketmp.workorder.event.WorkOrderStateChangedEvent;
import top.hetao.shiyuanticketmp.workorder.exception.WorkOrderException;
import top.hetao.shiyuanticketmp.workorder.mapper.WorkOrderMapper;
import top.hetao.shiyuanticketmp.workorder.service.WorkOrderService;
import top.hetao.shiyuanticketmp.workorder.service.WorkOrderTypeResolver;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 工单业务核心实现
 *
 * <p><b>业务状态机（有效流转路径）：</b>
 * <pre>
 *  PENDING（待处理）
 *      │
 *      ▼  assign()
 *  IN_PROGRESS（处理中）
 *      │                    │
 *      ▼  close()           ▼  reject()
 *  CLOSED（已关闭）     REJECTED（已驳回）
 *                          │
 *                          ▼  resubmit()  ← 提交人编辑后重新提交
 *                      PENDING（待处理）
 *
 *  管理员强制驳回：任意非 CLOSED → REJECTED（forceReject）
 * </pre>
 *
 * <p><b>WebHook 投递时机策略（两种模式）：</b>
 *
 * <p><b>① 普通异步模式（非关键事件）：</b>
 * 直接在 {@code @Transactional} 方法末尾调用 {@code webhookDispatcher.dispatch()}，
 * {@code @Async} 代理在当前线程提交事务后异步入队线程池执行投递。
 * 存在极小概率窗口期丢失（事务提交与异步任务入队之间进程崩溃），对非关键通知类事件可接受。
 * <ul>
 *   <li>适用：{@code WORK_ORDER.ASSIGNED}（派单通知）、{@code WORK_ORDER.REJECTED}（驳回通知）</li>
 * </ul>
 *
 * <p><b>② 严格事务后投递（强时效/资金相关事件）：</b>
 * 通过 {@link TransactionSynchronizationManager} 注册 {@code afterCommit} 回调，
 * Spring 在事务成功提交后才触发回调，回调内再调用 {@code @Async dispatch}，
 * 完全消除窗口期丢失风险，且回调本身不阻塞主流程。
 * <ul>
 *   <li>适用：{@code WORK_ORDER.CLOSED}（关单触发计费/结算，资金强相关）</li>
 * </ul>
 */
@Service
public class WorkOrderServiceImpl implements WorkOrderService {

    private static final Logger log = LoggerFactory.getLogger(WorkOrderServiceImpl.class);

    private static final String ACTION_CREATE    = "CREATE";
    private static final String ACTION_ASSIGN    = "ASSIGN";
    private static final String ACTION_CLOSE     = "CLOSE";
    private static final String ACTION_REJECT    = "REJECT";
    private static final String ACTION_RESUBMIT  = "RESUBMIT";
    private static final String ACTION_FORCE_REJECT = "FORCE_REJECT";

    private final WorkOrderMapper mapper;
    private final ApplicationEventPublisher eventPublisher;
    private final WorkOrderCacheManager cacheManager;
    private final WorkOrderTypeResolver typeResolver;
    private final SysRoleMapper roleMapper;

    public WorkOrderServiceImpl(WorkOrderMapper mapper,
                                ApplicationEventPublisher eventPublisher,
                                WorkOrderCacheManager cacheManager,
                                WorkOrderTypeResolver typeResolver,
                                SysRoleMapper roleMapper) {
        this.mapper = mapper;
        this.eventPublisher = eventPublisher;
        this.cacheManager = cacheManager;
        this.typeResolver = typeResolver;
        this.roleMapper = roleMapper;
    }

    @Override
    @Transactional(readOnly = true)
    public WorkOrder getById(Long workOrderId) {
        // 先查缓存
        WorkOrder cached = cacheManager.getWorkOrder(workOrderId);
        if (cached != null) {
            return cached;
        }
        // 缓存未命中，查数据库
        WorkOrder order = loadAndValidate(workOrderId, null);
        cacheManager.cacheWorkOrder(order);
        return order;
    }

    // ----------------------------------------------------------------
    // 创建工单
    // ----------------------------------------------------------------

    /**
     * 创建工单，初始状态为 {@code PENDING}，不触发 WebHook。
     *
     * <p>创建阶段不通知接收方，避免接收方在工单尚未分配前做无效处理。
     */
    @Override
    @Transactional
    public WorkOrder create(WorkOrder workOrder) {
        workOrder.setStatus(WorkOrderStatus.PENDING);
        if (workOrder.getType() == null) {
            workOrder.setType(typeResolver.resolve(workOrder.getTitle(), workOrder.getDescription()));
        }
        mapper.insert(workOrder);
        log.info("[工单] 创建成功 id={} title={} type={}", workOrder.getId(), workOrder.getTitle(), workOrder.getType());

        eventPublisher.publishEvent(new WorkOrderStateChangedEvent(
                this, workOrder, null, ACTION_CREATE,
                workOrder.getSubmitterId(), Map.of()));

        return workOrder;
    }

    // ----------------------------------------------------------------
    // 派发工单（非关键事件 → 普通异步模式）
    // ----------------------------------------------------------------

    /**
     * 将工单派发给指定处理人，状态 {@code PENDING → IN_PROGRESS}。
     *
     * <p><b>投递策略：普通异步模式（允许极小窗口期）</b><br>
     * {@code WORK_ORDER.ASSIGNED} 属于状态通知类事件，接收方（如消息系统）收到后推送站内信/邮件。
     * 即便极小概率丢失，管理员也可通过后台死信页面补推，不影响资金安全，故采用普通异步模式。
     *
     * @param workOrderId 工单主键
     * @param assigneeId  处理人 ID
     * @throws WorkOrderException 工单不存在或当前状态不是 PENDING 时
     */
    @Override
    @Transactional
    public WorkOrder assign(Long workOrderId, Long assigneeId) {
        WorkOrder order = loadAndValidate(workOrderId, WorkOrderStatus.PENDING);

        // 校验：只能派发给云仓侧人员（WAREHOUSE_ADMIN 角色）
        List<String> assigneeRoles = roleMapper.selectRoleCodesByUserId(assigneeId);
        if (!assigneeRoles.contains("WAREHOUSE_ADMIN")) {
            throw new WorkOrderException("只能派发给云仓管理人员");
        }

        order.setAssigneeId(assigneeId);
        order.setStatus(WorkOrderStatus.IN_PROGRESS);
        order.setAssignedAt(LocalDateTime.now());
        mapper.updateById(order);

        log.info("[工单] 派发成功 id={} assigneeId={}", workOrderId, assigneeId);

        eventPublisher.publishEvent(new WorkOrderStateChangedEvent(
                this, order, WorkOrderStatus.PENDING, ACTION_ASSIGN,
                assigneeId, Map.of("assigneeId", assigneeId)));

        return order;
    }

    // ----------------------------------------------------------------
    // 关闭工单（资金强相关 → 严格事务后投递）
    // ----------------------------------------------------------------

    /**
     * 关闭工单，状态 {@code IN_PROGRESS → CLOSED}。
     *
     * <p><b>投递策略：严格事务后投递（afterCommit）</b><br>
     * {@code WORK_ORDER.CLOSED} 为资金强相关事件，接收方（计费系统）将据此发起扣款/结算。
     * 若事务提交后立即崩溃导致事件丢失，将造成漏扣费等不可接受的资金问题，
     * 因此通过 {@link TransactionSynchronizationManager} 的 {@code afterCommit} 回调，
     * 确保在事务<b>确认提交成功</b>后才触发异步投递，彻底消除窗口期风险。
     *
     * <p><b>边界情况：</b>若事务回滚，{@code afterCommit} 不会被调用，WebHook 不发出，符合预期。
     *
     * @param workOrderId 工单主键
     * @param resolution  处理结论/备注
     * @throws WorkOrderException 工单不存在或当前状态不是 IN_PROGRESS 时
     */
    @Override
    @Transactional
    public WorkOrder close(Long workOrderId, String resolution) {
        WorkOrder order = loadAndValidate(workOrderId, WorkOrderStatus.IN_PROGRESS);

        order.setStatus(WorkOrderStatus.CLOSED);
        order.setResolution(resolution);
        order.setClosedAt(LocalDateTime.now());
        mapper.updateById(order);

        log.info("[工单] 关闭成功 id={}", workOrderId);

        eventPublisher.publishEvent(new WorkOrderStateChangedEvent(
                this, order, WorkOrderStatus.IN_PROGRESS, ACTION_CLOSE,
                order.getAssigneeId(), Map.of("resolution", resolution)));

        return order;
    }

    // ----------------------------------------------------------------
    // 驳回工单（非关键事件 → 普通异步模式）
    // ----------------------------------------------------------------

    /**
     * 驳回工单，状态 {@code IN_PROGRESS → REJECTED}（终态，不可再流转）。
     *
     * <p><b>投递策略：普通异步模式（允许极小窗口期）</b><br>
     * 驳回属于流程状态通知，不涉及资金，接受极小窗口期风险。
     *
     * @param workOrderId 工单主键
     * @param reason      驳回原因（记录到工单审计字段）
     * @throws WorkOrderException 工单不存在或当前状态不是 IN_PROGRESS 时
     */
    @Override
    @Transactional
    public WorkOrder reject(Long workOrderId, String reason) {
        WorkOrder order = loadAndValidate(workOrderId, WorkOrderStatus.IN_PROGRESS);

        order.setStatus(WorkOrderStatus.REJECTED);
        order.setRejectionReason(reason);
        order.setClosedAt(LocalDateTime.now());
        mapper.updateById(order);

        log.info("[工单] 驳回成功 id={} reason={}", workOrderId, reason);

        eventPublisher.publishEvent(new WorkOrderStateChangedEvent(
                this, order, WorkOrderStatus.IN_PROGRESS, ACTION_REJECT,
                order.getAssigneeId(), Map.of("reason", reason)));

        return order;
    }

    // ----------------------------------------------------------------
    // 被驳回工单重新提交（REJECTED → PENDING）
    // ----------------------------------------------------------------

    /**
     * 被驳回工单重新提交，状态 {@code REJECTED → PENDING}。
     *
     * <p>允许提交人编辑工单信息后重新提交。驳回原因和关闭时间会被清除。
     * 状态变更后触发 WebHook 通知。
     *
     * @param workOrderId 工单主键
     * @param updateData  更新后的工单信息（标题、描述、物流单号、目标地址、优先级）
     * @throws WorkOrderException 工单不存在或当前状态不是 REJECTED 时
     */
    @Override
    @Transactional
    public WorkOrder resubmit(Long workOrderId, WorkOrder updateData) {
        WorkOrder order = loadAndValidate(workOrderId, WorkOrderStatus.REJECTED);

        // 更新工单信息
        if (updateData.getTitle() != null && !updateData.getTitle().isBlank()) {
            order.setTitle(updateData.getTitle());
        }
        if (updateData.getDescription() != null) {
            order.setDescription(updateData.getDescription());
        }
        if (updateData.getTrackingNo() != null) {
            order.setTrackingNo(updateData.getTrackingNo());
        }
        if (updateData.getTargetAddress() != null) {
            order.setTargetAddress(updateData.getTargetAddress());
        }
        if (updateData.getPriority() != null) {
            order.setPriority(updateData.getPriority());
        }
        if (updateData.getType() != null) {
            order.setType(updateData.getType());
        }

        // 清除驳回信息，状态回到 PENDING
        order.setStatus(WorkOrderStatus.PENDING);
        order.setRejectionReason(null);
        order.setClosedAt(null);
        order.setAssigneeId(null);
        order.setAssignedAt(null);

        mapper.updateById(order);
        log.info("[工单] 重新提交成功 id={} title={}", workOrderId, order.getTitle());

        eventPublisher.publishEvent(new WorkOrderStateChangedEvent(
                this, order, WorkOrderStatus.REJECTED, ACTION_RESUBMIT,
                order.getSubmitterId(), Map.of()));

        return order;
    }

    // ----------------------------------------------------------------
    // 管理员强制驳回（任意非 CLOSED → REJECTED）
    // ----------------------------------------------------------------

    /**
     * 系统管理员强制驳回工单，任意非 CLOSED 状态均可驳回。
     *
     * <p>绕过常规状态校验（常规驳回要求 IN_PROGRESS），仅 CLOSED 状态不可驳回。
     * 状态变更后触发 WebHook 通知。
     *
     * @param workOrderId 工单主键
     * @param reason      驳回原因
     * @throws WorkOrderException 工单不存在或当前状态为 CLOSED 时
     */
    @Override
    @Transactional
    public WorkOrder forceReject(Long workOrderId, String reason) {
        WorkOrder order = loadAndValidate(workOrderId, null);

        // 仅 CLOSED 不可强制驳回
        if (order.getStatus() == WorkOrderStatus.CLOSED) {
            throw new WorkOrderException("已关闭的工单不可强制驳回");
        }

        WorkOrderStatus previousStatus = order.getStatus();
        order.setStatus(WorkOrderStatus.REJECTED);
        order.setRejectionReason(reason);
        order.setClosedAt(LocalDateTime.now());
        mapper.updateById(order);

        log.info("[工单] 管理员强制驳回 id={} 原状态={} reason={}", workOrderId, previousStatus, reason);

        eventPublisher.publishEvent(new WorkOrderStateChangedEvent(
                this, order, previousStatus, ACTION_FORCE_REJECT,
                null, Map.of("reason", reason, "forceReject", true)));

        return order;
    }

    // ----------------------------------------------------------------
    // 分页查询
    // ----------------------------------------------------------------

    @Override
    @Transactional(readOnly = true)
    public IPage<WorkOrder> listPage(int page, int pageSize, WorkOrderStatus status, String trackingNo,
                                     LocalDateTime createdStartTime, LocalDateTime createdEndTime) {
        LambdaQueryWrapper<WorkOrder> wrapper = buildQueryWrapper(status, trackingNo, createdStartTime, createdEndTime);
        return mapper.selectPage(new Page<>(page, pageSize), wrapper);
    }

    // ----------------------------------------------------------------
    // 批量派发
    // ----------------------------------------------------------------

    @Override
    @Transactional
    public int batchAssign(List<Long> workOrderIds, Long assigneeId) {
        if (workOrderIds == null || workOrderIds.isEmpty()) {
            throw new WorkOrderException("工单ID列表不能为空");
        }
        if (assigneeId == null) {
            throw new WorkOrderException("处理人ID不能为空");
        }

        int successCount = 0;
        List<String> errors = new ArrayList<>();

        for (Long orderId : workOrderIds) {
            try {
                assign(orderId, assigneeId);
                successCount++;
            } catch (WorkOrderException e) {
                errors.add("工单[" + orderId + "]: " + e.getMessage());
            }
        }

        if (!errors.isEmpty()) {
            log.warn("[工单] 批量派发部分失败: {}", String.join("; ", errors));
        }

        log.info("[工单] 批量派发完成 总数={} 成功={} 失败={}",
                workOrderIds.size(), successCount, errors.size());

        return successCount;
    }

    // ----------------------------------------------------------------
    // 导出查询
    // ----------------------------------------------------------------

    @Override
    @Transactional(readOnly = true)
    public List<WorkOrder> listForExport(WorkOrderStatus status, String trackingNo,
                                         LocalDateTime createdStartTime, LocalDateTime createdEndTime) {
        LambdaQueryWrapper<WorkOrder> wrapper = buildQueryWrapper(status, trackingNo, createdStartTime, createdEndTime);
        return mapper.selectList(wrapper);
    }

    private LambdaQueryWrapper<WorkOrder> buildQueryWrapper(WorkOrderStatus status, String trackingNo,
                                                            LocalDateTime createdStartTime,
                                                            LocalDateTime createdEndTime) {
        LambdaQueryWrapper<WorkOrder> wrapper = new LambdaQueryWrapper<>();
        if (status != null) {
            wrapper.eq(WorkOrder::getStatus, status);
        }
        if (trackingNo != null && !trackingNo.isBlank()) {
            wrapper.like(WorkOrder::getTrackingNo, trackingNo);
        }
        if (createdStartTime != null) {
            wrapper.ge(WorkOrder::getCreatedAt, createdStartTime);
        }
        if (createdEndTime != null) {
            wrapper.le(WorkOrder::getCreatedAt, createdEndTime);
        }
        wrapper.orderByDesc(WorkOrder::getCreatedAt);
        return wrapper;
    }

    // ----------------------------------------------------------------
    // 私有：加载工单 + 状态前置校验
    // ----------------------------------------------------------------

    /**
     * 按主键加载工单并校验当前状态是否符合操作前置条件。
     *
     * <p>集中处理加载 + 校验逻辑，各业务方法无需重复编写；
     * 使用 MyBatis-Plus 的 selectById 通用查询，自动携带 tenant_id 条件。
     *
     * @param workOrderId    工单主键
     * @param requiredStatus 执行当前操作所要求的前置状态
     * @return 已加载的工单实体
     * @throws WorkOrderException 工单不存在或状态不匹配时
     */
    private WorkOrder loadAndValidate(Long workOrderId, WorkOrderStatus requiredStatus) {
        WorkOrder order = mapper.selectById(workOrderId);
        if (order == null) {
            throw new WorkOrderException("工单不存在: " + workOrderId);
        }

        if (requiredStatus != null && order.getStatus() != requiredStatus) {
            throw new WorkOrderException(String.format(
                    "工单[%d]状态非法：期望 %s，实际 %s",
                    workOrderId, requiredStatus, order.getStatus()));
        }
        return order;
    }
}
