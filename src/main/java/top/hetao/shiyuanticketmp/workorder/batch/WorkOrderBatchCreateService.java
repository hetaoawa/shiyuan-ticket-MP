package top.hetao.shiyuanticketmp.workorder.batch;

import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import top.hetao.shiyuanticketmp.common.context.TenantContext;
import top.hetao.shiyuanticketmp.workorder.batch.entity.WorkOrderBatchRequest;
import top.hetao.shiyuanticketmp.workorder.batch.mapper.WorkOrderBatchRequestMapper;
import top.hetao.shiyuanticketmp.workorder.controller.dto.BatchCreateWorkOrderItem;
import top.hetao.shiyuanticketmp.workorder.controller.dto.BatchCreateWorkOrderRequest;
import top.hetao.shiyuanticketmp.workorder.entity.WorkOrder;
import top.hetao.shiyuanticketmp.workorder.enums.WorkOrderType;
import top.hetao.shiyuanticketmp.workorder.exception.WorkOrderException;
import top.hetao.shiyuanticketmp.workorder.service.WorkOrderService;

import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

@Service
public class WorkOrderBatchCreateService {
    public static final int MAX_BATCH_SIZE = 20;

    private final WorkOrderBatchRequestMapper batchRequestMapper;
    private final WorkOrderService workOrderService;
    private final ObjectMapper objectMapper;

    public WorkOrderBatchCreateService(WorkOrderBatchRequestMapper batchRequestMapper,
                                       WorkOrderService workOrderService,
                                       ObjectMapper objectMapper) {
        this.batchRequestMapper = batchRequestMapper;
        this.workOrderService = workOrderService;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public BatchCreateResult create(BatchCreateWorkOrderRequest request, String idempotencyKey,
                                    Long requesterId) {
        validateKey(idempotencyKey);
        if (requesterId == null) {
            throw new WorkOrderException("requesterId 不能为空");
        }
        List<BatchCreateWorkOrderItem> items = validateItems(request);
        Long tenantId = TenantContext.requireTenantId();
        String requestHash = hashCanonical(items);
        Long claimId = IdWorker.getId();

        int claimed = batchRequestMapper.insertClaim(
                claimId, tenantId, requesterId, idempotencyKey, requestHash);
        WorkOrderBatchRequest claim = batchRequestMapper.selectClaimForUpdate(
                tenantId, requesterId, idempotencyKey);
        if (claim == null) {
            throw new WorkOrderException("批量请求幂等记录创建失败");
        }
        if (!requestHash.equals(claim.getRequestHash())) {
            throw new BatchIdempotencyConflictException("Idempotency-Key 已用于不同的请求体");
        }
        if (claimed == 0 || claim.getWorkOrderIdsJson() != null) {
            if (claim.getWorkOrderIdsJson() == null) {
                throw new WorkOrderException("批量请求仍在处理中，请稍后重试");
            }
            return new BatchCreateResult(readIds(claim.getWorkOrderIdsJson()), true);
        }

        List<Long> ids = new ArrayList<>(items.size());
        for (BatchCreateWorkOrderItem item : items) {
            WorkOrder order = toWorkOrder(item, requesterId);
            ids.add(workOrderService.create(order).getId());
        }

        try {
            String idsJson = objectMapper.writeValueAsString(ids.stream().map(String::valueOf).toList());
            if (batchRequestMapper.completeClaim(claimId, idsJson) != 1) {
                throw new WorkOrderException("批量请求幂等记录写入失败");
            }
        } catch (WorkOrderException e) {
            throw e;
        } catch (Exception e) {
            throw new WorkOrderException("批量请求结果序列化失败", e);
        }
        return new BatchCreateResult(List.copyOf(ids), false);
    }

    static void validateKey(String key) {
        if (key == null || !key.matches("^[A-Za-z0-9_-]{8,64}$")) {
            throw new WorkOrderException("Idempotency-Key 必须为 8-64 位字母、数字、下划线或连字符");
        }
    }

    private List<BatchCreateWorkOrderItem> validateItems(BatchCreateWorkOrderRequest request) {
        if (request == null || request.getItems() == null || request.getItems().isEmpty()) {
            throw new WorkOrderException("items 不能为空");
        }
        if (request.getItems().size() > MAX_BATCH_SIZE) {
            throw new WorkOrderException("一次最多创建 " + MAX_BATCH_SIZE + " 个工单");
        }
        Set<String> trackingNumbers = new HashSet<>();
        Set<String> itemFingerprints = new HashSet<>();
        for (int i = 0; i < request.getItems().size(); i++) {
            BatchCreateWorkOrderItem item = request.getItems().get(i);
            if (item == null) {
                throw new WorkOrderException("items[" + i + "] 不能为空");
            }
            validateItem(item, i);
            String tracking = normalize(item.getTrackingNo());
            if (!tracking.isEmpty() && !trackingNumbers.add(tracking.toLowerCase(Locale.ROOT))) {
                throw new WorkOrderException("批内运单号重复: " + item.getTrackingNo().trim());
            }
            String fingerprint = canonicalItem(item).toString();
            if (!itemFingerprints.add(fingerprint)) {
                throw new WorkOrderException("批内存在重复工单: items[" + i + "]");
            }
        }
        return request.getItems();
    }

    private void validateItem(BatchCreateWorkOrderItem item, int index) {
        String prefix = "items[" + index + "].";
        if (item.getTitle() == null || item.getTitle().isBlank()) {
            throw new WorkOrderException(prefix + "title 不能为空");
        }
        if (item.getTitle().length() > 200) {
            throw new WorkOrderException(prefix + "title 不能超过 200 字符");
        }
        if (item.getTrackingNo() != null && item.getTrackingNo().length() > 50) {
            throw new WorkOrderException(prefix + "trackingNo 不能超过 50 字符");
        }
        if (item.getDescription() != null && item.getDescription().length() > 65535) {
            throw new WorkOrderException(prefix + "description 不能超过 65535 字符");
        }
        if (item.getTargetAddress() != null && item.getTargetAddress().length() > 500) {
            throw new WorkOrderException(prefix + "targetAddress 不能超过 500 字符");
        }
        if (item.getPriority() != null && (item.getPriority() < 1 || item.getPriority() > 3)) {
            throw new WorkOrderException(prefix + "priority 必须为 1、2 或 3");
        }
        if (item.getType() != null && !item.getType().isBlank()) {
            try {
                WorkOrderType.valueOf(item.getType());
            } catch (IllegalArgumentException e) {
                throw new WorkOrderException(prefix + "type 非法: " + item.getType());
            }
        }
    }

    private WorkOrder toWorkOrder(BatchCreateWorkOrderItem item, Long requesterId) {
        WorkOrder order = new WorkOrder();
        order.setTitle(item.getTitle().trim());
        order.setDescription(trimToNull(item.getDescription()));
        order.setTrackingNo(trimToNull(item.getTrackingNo()));
        order.setTargetAddress(trimToNull(item.getTargetAddress()));
        order.setPriority(item.getPriority() == null ? 2 : item.getPriority());
        order.setSubmitterId(requesterId);
        if (item.getType() != null && !item.getType().isBlank()) {
            order.setType(WorkOrderType.valueOf(item.getType()));
        }
        return order;
    }

    private String hashCanonical(List<BatchCreateWorkOrderItem> items) {
        try {
            List<Map<String, Object>> canonical = items.stream().map(this::canonicalItem).toList();
            byte[] json = objectMapper.writeValueAsBytes(canonical);
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(json));
        } catch (Exception e) {
            throw new WorkOrderException("批量请求规范化失败", e);
        }
    }

    private Map<String, Object> canonicalItem(BatchCreateWorkOrderItem item) {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("title", normalize(item.getTitle()));
        value.put("description", normalize(item.getDescription()));
        value.put("trackingNo", normalize(item.getTrackingNo()));
        value.put("targetAddress", normalize(item.getTargetAddress()));
        value.put("type", normalize(item.getType()));
        value.put("priority", item.getPriority() == null ? 2 : item.getPriority());
        return value;
    }

    private List<Long> readIds(String json) {
        try {
            List<String> values = objectMapper.readValue(json, new TypeReference<>() {});
            return values.stream().map(Long::valueOf).toList();
        } catch (Exception e) {
            throw new WorkOrderException("批量请求历史结果损坏", e);
        }
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim();
    }

    private static String trimToNull(String value) {
        String normalized = normalize(value);
        return normalized.isEmpty() ? null : normalized;
    }
}
