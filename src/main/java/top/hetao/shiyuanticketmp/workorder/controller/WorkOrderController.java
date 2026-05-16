package top.hetao.shiyuanticketmp.workorder.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import cn.dev33.satoken.stp.StpUtil;
import com.baomidou.mybatisplus.core.metadata.IPage;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.*;
import top.hetao.shiyuanticketmp.workorder.comment.entity.WorkOrderComment;
import top.hetao.shiyuanticketmp.workorder.comment.service.WorkOrderCommentService;
import top.hetao.shiyuanticketmp.workorder.controller.dto.AddCommentRequest;
import top.hetao.shiyuanticketmp.workorder.controller.dto.BatchAssignRequest;
import top.hetao.shiyuanticketmp.workorder.controller.dto.CommentVO;
import top.hetao.shiyuanticketmp.workorder.controller.dto.CreateWorkOrderRequest;
import top.hetao.shiyuanticketmp.workorder.controller.dto.ResubmitWorkOrderRequest;
import top.hetao.shiyuanticketmp.workorder.entity.WorkOrder;
import top.hetao.shiyuanticketmp.workorder.enums.WorkOrderStatus;
import top.hetao.shiyuanticketmp.workorder.enums.WorkOrderType;
import top.hetao.shiyuanticketmp.workorder.service.WorkOrderService;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

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

    public WorkOrderController(WorkOrderService workOrderService,
                               WorkOrderCommentService commentService) {
        this.workOrderService = workOrderService;
        this.commentService = commentService;
    }

    /**
     * 创建工单。
     *
     * <p>需要 {@code workorder:create} 权限。
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
        order.setSubmitterId(StpUtil.getLoginIdAsLong());

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

    /**
     * 分页查询工单列表。
     *
     * <p>支持按状态筛选、按物流单号模糊搜索，租户拦截器自动隔离数据。
     */
    @GetMapping
    public Map<String, Object> list(@RequestParam(defaultValue = "1") int page,
                                     @RequestParam(defaultValue = "10") int pageSize,
                                     @RequestParam(required = false) WorkOrderStatus status,
                                     @RequestParam(required = false) String trackingNo,
                                     @RequestParam(required = false) @DateTimeFormat(pattern = "yyyy-MM-dd HH:mm:ss") LocalDateTime createdStartTime,
                                     @RequestParam(required = false) @DateTimeFormat(pattern = "yyyy-MM-dd HH:mm:ss") LocalDateTime createdEndTime) {
        IPage<WorkOrder> pageResult = workOrderService.listPage(page, pageSize, status, trackingNo, createdStartTime, createdEndTime);

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
     * <p>需要登录即可（租户拦截器自动隔离数据）。
     */
    @GetMapping("/{id}")
    public Map<String, Object> detail(@PathVariable Long id) {
        WorkOrder order = workOrderService.getById(id);
        Map<String, Object> result = new HashMap<>();
        result.put("code", 200);
        result.put("data", order);
        return result;
    }

    /**
     * 派发工单（分配处理人）。
     *
     * <p>需要 {@code workorder:assign} 权限。
     */
    @PostMapping("/{id}/assign")
    @SaCheckPermission("workorder:assign")
    public Map<String, Object> assign(@PathVariable Long id,
                                      @RequestParam Long assigneeId) {
        WorkOrder order = workOrderService.assign(id, assigneeId);

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
     * 批量派发工单（选中多个 PENDING 状态工单派发给同一处理人）。
     *
     * <p>需要 {@code workorder:assign} 权限。
     */
    @PostMapping("/batch-assign")
    @SaCheckPermission("workorder:assign")
    public Map<String, Object> batchAssign(@RequestBody BatchAssignRequest request) {
        int successCount = workOrderService.batchAssign(request.getWorkOrderIds(), request.getAssigneeId());

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
        WorkOrderComment comment = commentService.addComment(
                id, request.getContent(), commenterId,
                request.getCommentType(), request.getAttachments());

        Map<String, Object> result = new HashMap<>();
        result.put("code", 200);
        result.put("message", "评论成功");
        result.put("data", comment);
        return result;
    }

    /**
     * 获取工单评论列表（分页）。
     */
    @GetMapping("/{id}/comments")
    public Map<String, Object> listComments(@PathVariable Long id,
                                            @RequestParam(defaultValue = "1") int page,
                                            @RequestParam(defaultValue = "20") int pageSize) {
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
        List<WorkOrder> orders = workOrderService.listForExport(status, trackingNo, createdStartTime, createdEndTime);

        StringBuilder csv = new StringBuilder();
        // CSV header (BOM for Excel)
        csv.append("\uFEFF");
        csv.append("工单ID,标题,描述,物流单号,目标地址,类型,优先级,状态,提交人ID,处理人ID,处理结论,驳回原因,创建时间,派发时间,关闭时间\n");

        for (WorkOrder o : orders) {
            csv.append(o.getId()).append(",");
            csv.append(escapeCsv(o.getTitle())).append(",");
            csv.append(escapeCsv(o.getDescription())).append(",");
            csv.append(escapeCsv(o.getTrackingNo())).append(",");
            csv.append(escapeCsv(o.getTargetAddress())).append(",");
            csv.append(o.getType() != null ? o.getType().name() : "").append(",");
            csv.append(o.getPriority()).append(",");
            csv.append(o.getStatus().name()).append(",");
            csv.append(o.getSubmitterId()).append(",");
            csv.append(o.getAssigneeId() != null ? o.getAssigneeId() : "").append(",");
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
}
