package top.hetao.shiyuanticketmp.workorder.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import cn.dev33.satoken.stp.StpUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.*;
import top.hetao.shiyuanticketmp.audit.entity.SysAuditLog;
import top.hetao.shiyuanticketmp.audit.service.AuditLogService;
import top.hetao.shiyuanticketmp.auth.entity.SysRole;
import top.hetao.shiyuanticketmp.auth.entity.SysUser;
import top.hetao.shiyuanticketmp.auth.mapper.SysRoleMapper;
import top.hetao.shiyuanticketmp.auth.service.UserService;
import top.hetao.shiyuanticketmp.workorder.comment.entity.WorkOrderComment;
import top.hetao.shiyuanticketmp.workorder.comment.service.WorkOrderCommentService;
import top.hetao.shiyuanticketmp.workorder.batch.BatchCreateResult;
import top.hetao.shiyuanticketmp.workorder.batch.BatchIdempotencyConflictException;
import top.hetao.shiyuanticketmp.workorder.batch.WorkOrderBatchCreateService;
import top.hetao.shiyuanticketmp.workorder.controller.dto.AddCommentRequest;
import top.hetao.shiyuanticketmp.workorder.controller.dto.BatchAssignRequest;
import top.hetao.shiyuanticketmp.workorder.controller.dto.BatchCreateWorkOrderRequest;
import top.hetao.shiyuanticketmp.workorder.controller.dto.CommentVO;
import top.hetao.shiyuanticketmp.workorder.controller.dto.CreateWorkOrderRequest;
import top.hetao.shiyuanticketmp.workorder.controller.dto.ResubmitWorkOrderRequest;
import top.hetao.shiyuanticketmp.workorder.entity.WorkOrder;
import top.hetao.shiyuanticketmp.workorder.enums.WorkOrderStatus;
import top.hetao.shiyuanticketmp.workorder.enums.WorkOrderType;
import top.hetao.shiyuanticketmp.workorder.exception.WorkOrderException;
import top.hetao.shiyuanticketmp.workorder.service.WorkOrderService;
import top.hetao.shiyuanticketmp.common.context.TenantContext;
import top.hetao.shiyuanticketmp.tenant.service.TenantService;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 工单 REST API 控制器。
 *
 * <p>所有接口均需登录，部分接口需要特定权限。
 */
@RestController
@RequestMapping("/api/workorders")
public class WorkOrderController {

    private final WorkOrderService workOrderService;
    private final WorkOrderCommentService commentService;
    private final UserService userService;
    private final SysRoleMapper roleMapper;
    private final TenantService tenantService;
    private final WorkOrderBatchCreateService batchCreateService;
    private final AuditLogService auditLogService;

    public WorkOrderController(WorkOrderService workOrderService,
                               WorkOrderCommentService commentService,
                               UserService userService,
                               SysRoleMapper roleMapper,
                               TenantService tenantService,
                               WorkOrderBatchCreateService batchCreateService,
                               AuditLogService auditLogService) {
        this.workOrderService = workOrderService;
        this.commentService = commentService;
        this.userService = userService;
        this.roleMapper = roleMapper;
        this.tenantService = tenantService;
        this.batchCreateService = batchCreateService;
        this.auditLogService = auditLogService;
    }

    /**
     * 创建工单。
     *
     * <p>需要 {@code workorder:create} 权限。
     * 若传入 {@code senderStaffId}，则通过外部用户 ID 映射提交人；
     * 否则使用当前登录用户作为提交人。
     */
    @PostMapping
    @SaCheckPermission("workorder:create")
    public Map<String, Object> create(@RequestBody CreateWorkOrderRequest request) {
        WorkOrder order = new WorkOrder();
        order.setTitle(request.getTitle());
        order.setDescription(request.getDescription());
        order.setTrackingNo(request.getTrackingNo());
        order.setTargetAddress(request.getTargetAddress());
        order.setPriority(request.getPriority() != null ? request.getPriority() : 2);

        // 外部用户 ID 关联逻辑
        String senderStaffId = request.getSenderStaffId();
        if (senderStaffId != null && !senderStaffId.isBlank()) {
            SysUser externalUser = userService.getByExternalUserIdIgnoreTenant(senderStaffId);
            if (externalUser == null) {
                throw new WorkOrderException("未找到外部用户ID对应的系统用户: " + senderStaffId);
            }
            order.setSubmitterId(externalUser.getId());
            order.setSenderStaffId(senderStaffId);
            // 继承外部系统用户的租户，避免 SYSTEM_ADMIN（租户 0）创建时落到错误租户
            order.setTenantId(externalUser.getTenantId());
        } else {
            order.setSubmitterId(StpUtil.getLoginIdAsLong());
        }

        String conversationId = request.getConversationId();
        if (conversationId != null && !conversationId.isBlank()) {
            order.setConversationId(conversationId);
        }

        // 如果前端传了类型则使用，否则由 Service 层自动解析
        if (request.getType() != null && !request.getType().isBlank()) {
            order.setType(WorkOrderType.valueOf(request.getType()));
        }

        WorkOrder created = workOrderService.create(order);

        Map<String, Object> result = new HashMap<>();
        result.put("code", 200);
        result.put("message", "创建成功");
        result.put("data", created);
        return result;
    }

    @PostMapping("/batch")
    @SaCheckPermission("workorder:create")
    public ResponseEntity<Map<String, Object>> createBatch(
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @RequestBody BatchCreateWorkOrderRequest request) {
        try {
            BatchCreateResult created = batchCreateService.create(
                    request, idempotencyKey, StpUtil.getLoginIdAsLong());
            Map<String, Object> data = new HashMap<>();
            data.put("workOrderIds", created.workOrderIds());
            data.put("replayed", created.replayed());
            return ResponseEntity.ok(Map.of(
                    "code", 200,
                    "message", created.replayed() ? "幂等重放成功" : "批量创建成功",
                    "data", data));
        } catch (BatchIdempotencyConflictException e) {
            return ResponseEntity.status(409).body(Map.of(
                    "code", 409,
                    "message", e.getMessage()));
        }
    }

    /**
     * 分页查询工单列表。
     *
     * <p>支持按状态筛选、按物流单号模糊搜索，带可见性过滤。
     */
    @GetMapping
    @SaCheckPermission("workorder:view")
    public Map<String, Object> list(@RequestParam(defaultValue = "1") int page,
                                     @RequestParam(defaultValue = "10") int pageSize,
                                     @RequestParam(required = false) WorkOrderStatus status,
                                     @RequestParam(required = false) String trackingNo,
                                     @RequestParam(required = false) @DateTimeFormat(pattern = "yyyy-MM-dd HH:mm:ss") LocalDateTime createdStartTime,
                                     @RequestParam(required = false) @DateTimeFormat(pattern = "yyyy-MM-dd HH:mm:ss") LocalDateTime createdEndTime) {
        Long currentUserId = StpUtil.getLoginIdAsLong();
        List<String> currentUserRoles = userService.getRoleCodes(currentUserId);
        IPage<WorkOrder> pageResult = workOrderService.listPage(page, pageSize, status, trackingNo,
                createdStartTime, createdEndTime, currentUserId, currentUserRoles);

        enrichWorkOrders(pageResult.getRecords());

        Map<String, Object> result = new HashMap<>();
        result.put("code", 200);
        result.put("data", pageResult.getRecords());
        result.put("total", pageResult.getTotal());
        result.put("page", page);
        result.put("pageSize", pageSize);
        return result;
    }

    /**
     * 查询工单详情。
     *
     * <p>需要登录且有权限查看该工单。
     */
    @GetMapping("/{id}")
    @SaCheckPermission("workorder:view")
    public Map<String, Object> detail(@PathVariable Long id) {
        Long currentUserId = StpUtil.getLoginIdAsLong();
        List<String> currentUserRoles = userService.getRoleCodes(currentUserId);
        WorkOrder order = workOrderService.getByIdWithAccessCheck(id, currentUserId, currentUserRoles);
        enrichWorkOrder(order);
        Map<String, Object> result = new HashMap<>();
        result.put("code", 200);
        result.put("data", order);
        return result;
    }

    /**
     * 查询当前用户可见工单的处理轨迹。该接口只要求工单查看权限，
     * 不扩大系统审计页的 audit:view 权限。
     */
    @GetMapping("/{id}/audit-logs")
    @SaCheckPermission("workorder:view")
    public Map<String, Object> auditLogs(@PathVariable Long id) {
        Long currentUserId = StpUtil.getLoginIdAsLong();
        List<String> currentUserRoles = userService.getRoleCodes(currentUserId);
        workOrderService.getByIdWithAccessCheck(id, currentUserId, currentUserRoles);
        AuditLogService.TimelineResult timeline = auditLogService.getWorkOrderTimeline(id);

        Map<String, Object> result = new HashMap<>();
        result.put("code", 200);
        result.put("message", "success");
        result.put("data", timeline.records());
        result.put("total", timeline.total());
        result.put("truncated", timeline.truncated());
        result.put("order", "ASC");
        return result;
    }

    /**
     * 派发工单（分配处理人或按角色派发）。
     *
     * <p>需要 {@code workorder:assign} 权限。
     * 参数 {@code assigneeId} 和 {@code assigneeRoleCode} 必选其一且互斥。
     */
    @PostMapping("/{id}/assign")
    @SaCheckPermission("workorder:assign")
    public Map<String, Object> assign(@PathVariable Long id,
                                      @RequestParam(required = false) Long assigneeId,
                                      @RequestParam(required = false) String assigneeRoleCode) {
        requireCurrentActorAccess(id);
        boolean hasUser = assigneeId != null;
        boolean hasRole = assigneeRoleCode != null && !assigneeRoleCode.isBlank();
        if (!hasUser && !hasRole) {
            throw new WorkOrderException("必须指定 assigneeId 或 assigneeRoleCode");
        }
        if (hasUser && hasRole) {
            throw new WorkOrderException("assigneeId 和 assigneeRoleCode 不能同时指定");
        }

        WorkOrder order;
        if (hasUser) {
            order = workOrderService.assign(id, assigneeId);
        } else {
            order = workOrderService.assignByRole(id, assigneeRoleCode);
        }

        Map<String, Object> result = new HashMap<>();
        result.put("code", 200);
        result.put("message", "派发成功");
        result.put("data", order);
        return result;
    }

    /**
     * 关闭工单。
     *
     * <p>需要 {@code workorder:close} 权限。
     */
    @PostMapping("/{id}/close")
    @SaCheckPermission("workorder:close")
    public Map<String, Object> close(@PathVariable Long id,
                                     @RequestParam String resolution) {
        requireCurrentActorAccess(id);
        WorkOrder order = workOrderService.close(id, resolution);

        Map<String, Object> result = new HashMap<>();
        result.put("code", 200);
        result.put("message", "关闭成功");
        result.put("data", order);
        return result;
    }

    /**
     * 驳回工单。
     *
     * <p>需要 {@code workorder:reject} 权限。
     */
    @PostMapping("/{id}/reject")
    @SaCheckPermission("workorder:reject")
    public Map<String, Object> reject(@PathVariable Long id,
                                      @RequestParam String reason) {
        requireCurrentActorAccess(id);
        WorkOrder order = workOrderService.reject(id, reason);

        Map<String, Object> result = new HashMap<>();
        result.put("code", 200);
        result.put("message", "驳回成功");
        result.put("data", order);
        return result;
    }

    /**
     * 被驳回工单重新提交。
     *
     * <p>允许提交人编辑工单信息后重新提交，状态 REJECTED → PENDING。
     * 需要 {@code workorder:resubmit} 权限。
     */
    @PostMapping("/{id}/resubmit")
    @SaCheckPermission("workorder:resubmit")
    public Map<String, Object> resubmit(@PathVariable Long id,
                                        @RequestBody ResubmitWorkOrderRequest request) {
        requireCurrentActorAccess(id);
        WorkOrder updateData = new WorkOrder();
        updateData.setTitle(request.getTitle());
        updateData.setDescription(request.getDescription());
        updateData.setTrackingNo(request.getTrackingNo());
        updateData.setTargetAddress(request.getTargetAddress());
        updateData.setPriority(request.getPriority());
        if (request.getType() != null && !request.getType().isBlank()) {
            updateData.setType(WorkOrderType.valueOf(request.getType()));
        }

        WorkOrder order = workOrderService.resubmit(id, updateData);

        Map<String, Object> result = new HashMap<>();
        result.put("code", 200);
        result.put("message", "重新提交成功");
        result.put("data", order);
        return result;
    }

    /**
     * 系统管理员强制驳回工单。
     *
     * <p>绕过常规状态校验，任意非 CLOSED 状态均可驳回。
     * 需要 {@code workorder:force-reject} 权限（仅 SYSTEM_ADMIN）。
     */
    @PostMapping("/{id}/force-reject")
    @SaCheckPermission("workorder:force-reject")
    public Map<String, Object> forceReject(@PathVariable Long id,
                                           @RequestParam String reason) {
        requireCurrentActorAdministratorAccess(id);
        WorkOrder order = workOrderService.forceReject(id, reason);

        Map<String, Object> result = new HashMap<>();
        result.put("code", 200);
        result.put("message", "强制驳回成功");
        result.put("data", order);
        return result;
    }

    // ----------------------------------------------------------------
    // 批量派发
    // ----------------------------------------------------------------

    /**
     * 批量派发工单（选中多个 PENDING 状态工单派发给同一处理人或角色）。
     *
     * <p>需要 {@code workorder:assign} 权限。
     * 支持按用户（assigneeId）或按角色（assigneeRoleCode）派发，二者必选其一。
     */
    @PostMapping("/batch-assign")
    @SaCheckPermission("workorder:assign")
    public Map<String, Object> batchAssign(@RequestBody BatchAssignRequest request) {
        if (request.getWorkOrderIds() == null || request.getWorkOrderIds().isEmpty()) {
            throw new WorkOrderException("工单ID列表不能为空");
        }
        for (Long workOrderId : request.getWorkOrderIds()) {
            if (workOrderId == null) {
                throw new WorkOrderException("工单ID不能为空");
            }
            requireCurrentActorAccess(workOrderId);
        }
        boolean hasUser = request.getAssigneeId() != null;
        boolean hasRole = request.getAssigneeRoleCode() != null && !request.getAssigneeRoleCode().isBlank();
        if (!hasUser && !hasRole) {
            throw new WorkOrderException("必须指定 assigneeId 或 assigneeRoleCode");
        }
        if (hasUser && hasRole) {
            throw new WorkOrderException("assigneeId 和 assigneeRoleCode 不能同时指定");
        }

        int successCount;
        if (hasUser) {
            successCount = workOrderService.batchAssign(request.getWorkOrderIds(), request.getAssigneeId());
        } else {
            successCount = workOrderService.batchAssignByRole(request.getWorkOrderIds(), request.getAssigneeRoleCode());
        }

        Map<String, Object> result = new HashMap<>();
        result.put("code", 200);
        result.put("message", "批量派发完成");
        result.put("data", Map.of(
                "total", request.getWorkOrderIds().size(),
                "success", successCount,
                "failed", request.getWorkOrderIds().size() - successCount
        ));
        return result;
    }

    @GetMapping("/assignment-options/users")
    @SaCheckPermission("workorder:assign")
    public Map<String, Object> assignmentUsers() {
        List<Map<String, Object>> users = userService.listActiveUsersByRoleCode("WAREHOUSE_ADMIN")
                .stream().map(user -> {
                    Map<String, Object> option = new HashMap<>();
                    option.put("id", user.getId());
                    option.put("username", user.getUsername());
                    option.put("nickname", user.getNickname());
                    return option;
                }).toList();
        return Map.of("code", 200, "data", users);
    }

    @GetMapping("/assignment-options/roles")
    @SaCheckPermission("workorder:assign")
    public Map<String, Object> assignmentRoles() {
        List<Map<String, Object>> roles = roleMapper.selectList(new LambdaQueryWrapper<SysRole>()
                        .eq(SysRole::getRoleCode, "WAREHOUSE_ADMIN"))
                .stream().map(role -> {
                    Map<String, Object> option = new HashMap<>();
                    option.put("id", role.getId());
                    option.put("roleCode", role.getRoleCode());
                    option.put("roleName", role.getRoleName());
                    return option;
                }).toList();
        return Map.of("code", 200, "data", roles);
    }

    private void requireCurrentActorAccess(Long workOrderId) {
        Long currentUserId = StpUtil.getLoginIdAsLong();
        List<String> roles = userService.getRoleCodes(currentUserId);
        workOrderService.getByIdWithAccessCheck(workOrderId, currentUserId, roles);
    }

    private void requireCurrentActorAdministratorAccess(Long workOrderId) {
        Long currentUserId = StpUtil.getLoginIdAsLong();
        List<String> roles = userService.getRoleCodes(currentUserId);
        if (!roles.contains("SYSTEM_ADMIN") && !roles.contains("GLOBAL_SYSTEM_ADMIN")) {
            throw new WorkOrderException("仅系统管理员可强制驳回工单");
        }
        workOrderService.getByIdWithAccessCheck(workOrderId, currentUserId, roles);
    }

    // ----------------------------------------------------------------
    // 评论/备注
    // ----------------------------------------------------------------

    /**
     * 添加工单评论。
     *
     * <p>需要 {@code workorder:comment} 权限。
     */
    @PostMapping("/{id}/comments")
    @SaCheckPermission("workorder:comment")
    public Map<String, Object> addComment(@PathVariable Long id,
                                          @RequestBody AddCommentRequest request) {
        Long commenterId = StpUtil.getLoginIdAsLong();
        List<String> commenterRoles = userService.getRoleCodes(commenterId);
        WorkOrderComment comment = commentService.addComment(
                id, request.getContent(), commenterId,
                request.getCommentType(), request.getAttachments(), commenterRoles);

        Map<String, Object> result = new HashMap<>();
        result.put("code", 200);
        result.put("message", "评论成功");
        result.put("data", comment);
        return result;
    }

    /**
     * 获取工单评论列表（分页）。
     *
     * <p>需要登录且有权限查看该工单。
     */
    @GetMapping("/{id}/comments")
    @SaCheckPermission("workorder:view")
    public Map<String, Object> listComments(@PathVariable Long id,
                                            @RequestParam(defaultValue = "1") int page,
                                            @RequestParam(defaultValue = "20") int pageSize) {
        Long currentUserId = StpUtil.getLoginIdAsLong();
        List<String> currentUserRoles = userService.getRoleCodes(currentUserId);
        workOrderService.getByIdWithAccessCheck(id, currentUserId, currentUserRoles);
        IPage<CommentVO> pageResult = commentService.listByWorkOrderWithUser(id, page, pageSize);

        Map<String, Object> result = new HashMap<>();
        result.put("code", 200);
        result.put("data", pageResult.getRecords());
        result.put("total", pageResult.getTotal());
        result.put("page", page);
        result.put("pageSize", pageSize);
        return result;
    }

    // ----------------------------------------------------------------
    // 工单导出
    // ----------------------------------------------------------------

    private static final DateTimeFormatter CSV_DATE_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    /**
     * 导出工单列表为 CSV 文件。
     *
     * <p>需要 {@code workorder:export} 权限。支持与列表相同的筛选条件。
     */
    @GetMapping("/export")
    @SaCheckPermission("workorder:export")
    public ResponseEntity<byte[]> export(@RequestParam(required = false) WorkOrderStatus status,
                                         @RequestParam(required = false) String trackingNo,
                                         @RequestParam(required = false) @DateTimeFormat(pattern = "yyyy-MM-dd HH:mm:ss") LocalDateTime createdStartTime,
                                         @RequestParam(required = false) @DateTimeFormat(pattern = "yyyy-MM-dd HH:mm:ss") LocalDateTime createdEndTime) {
        Long currentUserId = StpUtil.getLoginIdAsLong();
        List<String> currentUserRoles = userService.getRoleCodes(currentUserId);
        List<WorkOrder> orders = workOrderService.listForExport(status, trackingNo, createdStartTime, createdEndTime,
                currentUserId, currentUserRoles);

        StringBuilder csv = new StringBuilder();
        // CSV header (BOM for Excel)
        csv.append("\uFEFF");
        csv.append("工单ID,租户ID,租户名称,标题,描述,物流单号,目标地址,类型,优先级,状态,提交人ID,处理人ID,派发角色,处理结论,驳回原因,创建时间,派发时间,关闭时间\n");

        String exportTenantName = tenantService.getTenantName(TenantContext.requireTenantId());
        for (WorkOrder o : orders) {
            csv.append(o.getId()).append(",");
            csv.append(o.getTenantId()).append(",");
            csv.append(escapeCsv(exportTenantName)).append(",");
            csv.append(escapeCsv(o.getTitle())).append(",");
            csv.append(escapeCsv(o.getDescription())).append(",");
            csv.append(escapeCsv(o.getTrackingNo())).append(",");
            csv.append(escapeCsv(o.getTargetAddress())).append(",");
            csv.append(o.getType() != null ? o.getType().name() : "").append(",");
            csv.append(o.getPriority()).append(",");
            csv.append(o.getStatus().name()).append(",");
            csv.append(o.getSubmitterId()).append(",");
            csv.append(o.getAssigneeId() != null ? o.getAssigneeId() : "").append(",");
            csv.append(escapeCsv(o.getAssigneeRole())).append(",");
            csv.append(escapeCsv(o.getResolution())).append(",");
            csv.append(escapeCsv(o.getRejectionReason())).append(",");
            csv.append(o.getCreatedAt() != null ? o.getCreatedAt().format(CSV_DATE_FMT) : "").append(",");
            csv.append(o.getAssignedAt() != null ? o.getAssignedAt().format(CSV_DATE_FMT) : "").append(",");
            csv.append(o.getClosedAt() != null ? o.getClosedAt().format(CSV_DATE_FMT) : "").append("\n");
        }

        byte[] bytes = csv.toString().getBytes(StandardCharsets.UTF_8);

        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=workorders.csv")
                .contentType(MediaType.parseMediaType("text/csv; charset=UTF-8"))
                .contentLength(bytes.length)
                .body(bytes);
    }

    /**
     * CSV 字段转义：如果包含逗号、双引号或换行，用双引号包裹并转义内部双引号。
     */
    private String escapeCsv(String value) {
        if (value == null) {
            return "";
        }
        if (value.contains(",") || value.contains("\"") || value.contains("\n")) {
            return "\"" + value.replace("\"", "\"\"") + "\"";
        }
        return value;
    }

    // ----------------------------------------------------------------
    // 批量填充处理人/提交人展示名称
    // ----------------------------------------------------------------

    /**
     * 批量填充工单的处理人名称和角色名称，避免 N+1 查询。
     */
    private void enrichWorkOrders(List<WorkOrder> orders) {
        if (orders == null || orders.isEmpty()) return;
        String tenantName = tenantService.getTenantName(TenantContext.requireTenantId());

        // 收集需要查询的用户 ID
        Set<Long> userIds = orders.stream()
                .map(WorkOrder::getAssigneeId)
                .filter(id -> id != null)
                .collect(Collectors.toSet());
        orders.stream()
                .map(WorkOrder::getSubmitterId)
                .filter(id -> id != null)
                .forEach(userIds::add);

        // 批量查询用户
        Map<Long, String> userNameMap = new HashMap<>();
        if (!userIds.isEmpty()) {
            for (Long uid : userIds) {
                SysUser user = userService.getById(uid);
                if (user != null) {
                    userNameMap.put(uid, user.getNickname() != null ? user.getNickname() : user.getUsername());
                }
            }
        }
        // 全局管理员切入业务租户后可以作为工单提交人；仅对工单已引用的提交人 ID
        // 回查平台租户用户，避免将任意其他业务租户用户带入当前租户响应。
        orders.stream()
                .map(WorkOrder::getSubmitterId)
                .filter(id -> id != null && !userNameMap.containsKey(id))
                .distinct()
                .forEach(id -> {
                    String platformName = resolvePlatformUserName(id);
                    if (platformName != null) {
                        userNameMap.put(id, platformName);
                    }
                });

        // 收集需要查询的角色编码
        Set<String> roleCodes = orders.stream()
                .map(WorkOrder::getAssigneeRole)
                .filter(rc -> rc != null && !rc.isBlank())
                .collect(Collectors.toSet());

        // 批量查询角色名称
        Map<String, String> roleNameMap = new HashMap<>();
        if (!roleCodes.isEmpty()) {
            for (String rc : roleCodes) {
                List<SysRole> roles = roleMapper.selectList(
                        new LambdaQueryWrapper<SysRole>()
                                .eq(SysRole::getRoleCode, rc)
                                .last("LIMIT 1"));
                if (!roles.isEmpty() && roles.get(0).getRoleName() != null) {
                    roleNameMap.put(rc, roles.get(0).getRoleName());
                }
            }
        }

        // 填充
        for (WorkOrder order : orders) {
            order.setTenantName(tenantName);
            enrichWorkOrder(order, userNameMap, roleNameMap);
        }
    }

    private void enrichWorkOrder(WorkOrder order) {
        if (order == null) return;
        order.setTenantName(tenantService.getTenantName(order.getTenantId()));
        Map<Long, String> userNameMap = new HashMap<>();
        Map<String, String> roleNameMap = new HashMap<>();
        enrichWorkOrder(order, userNameMap, roleNameMap);
    }

    private void enrichWorkOrder(WorkOrder order, Map<Long, String> userNameMap, Map<String, String> roleNameMap) {
        if (order.getAssigneeId() != null) {
            String name = userNameMap.get(order.getAssigneeId());
            if (name == null) {
                SysUser user = userService.getById(order.getAssigneeId());
                name = user != null ? (user.getNickname() != null ? user.getNickname() : user.getUsername()) : null;
            }
            order.setAssigneeName(name);
        }
        if (order.getAssigneeRole() != null && !order.getAssigneeRole().isBlank()) {
            String roleName = roleNameMap.get(order.getAssigneeRole());
            if (roleName == null) {
                List<SysRole> roles = roleMapper.selectList(
                        new LambdaQueryWrapper<SysRole>()
                                .eq(SysRole::getRoleCode, order.getAssigneeRole())
                                .last("LIMIT 1"));
                if (!roles.isEmpty()) {
                    roleName = roles.get(0).getRoleName();
                }
            }
            order.setAssigneeRoleName(roleName);
        }
        if (order.getSubmitterId() != null) {
            String name = userNameMap.get(order.getSubmitterId());
            if (name == null) {
                SysUser user = userService.getById(order.getSubmitterId());
                name = user != null ? (user.getNickname() != null ? user.getNickname() : user.getUsername()) : null;
            }
            if (name == null) {
                name = resolvePlatformUserName(order.getSubmitterId());
            }
            order.setSubmitterName(name);
        }
    }

    private String resolvePlatformUserName(Long userId) {
        SysUser user = userService.getByIdIgnoreTenant(userId);
        if (user == null || !Long.valueOf(0L).equals(user.getTenantId())) {
            return null;
        }
        return user.getNickname() != null ? user.getNickname() : user.getUsername();
    }
}
