package top.hetao.shiyuanticketmp.webhook.receiver;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import top.hetao.shiyuanticketmp.ai.AiParseService;
import top.hetao.shiyuanticketmp.auth.entity.SysUser;
import top.hetao.shiyuanticketmp.common.context.TenantContext;
import top.hetao.shiyuanticketmp.auth.service.UserService;
import top.hetao.shiyuanticketmp.workorder.entity.WorkOrder;
import top.hetao.shiyuanticketmp.workorder.enums.WorkOrderStatus;
import top.hetao.shiyuanticketmp.workorder.enums.WorkOrderType;
import top.hetao.shiyuanticketmp.workorder.service.WorkOrderService;

import java.util.Map;

/**
 * 货主侧入站消息接收端点。
 *
 * <p>验签流程：
 * <ol>
 *   <li>校验 appId 请求头</li>
 *   <li>对请求体做 MD5 → RSA 私钥解密 sign → 比较摘要</li>
 *   <li>验证通过后，调用 AI 解析消息内容并创建工单</li>
 * </ol>
 */
@RestController
@RequestMapping("/api/webhook/cargo-owner")
public class CargoOwnerWebhookController {

    private static final Logger log = LoggerFactory.getLogger(CargoOwnerWebhookController.class);
    private static final int MAX_CONTENT_LENGTH = 300;

    private final WorkOrderService workOrderService;
    private final ObjectMapper objectMapper;
    private final CargoOwnerSignVerifier signVerifier;
    private final AiParseService aiParseService;
    private final UserService userService;

    public CargoOwnerWebhookController(WorkOrderService workOrderService,
                                         ObjectMapper objectMapper,
                                         CargoOwnerSignVerifier signVerifier,
                                         AiParseService aiParseService,
                                         UserService userService) {
        this.workOrderService = workOrderService;
        this.objectMapper = objectMapper;
        this.signVerifier = signVerifier;
        this.aiParseService = aiParseService;
        this.userService = userService;
    }

    /**
     * 接收货主侧消息并创建工单。
     */
    @PostMapping
    public ResponseEntity<Map<String, Object>> receive(HttpServletRequest request,
                                                        @RequestBody String body) {
        // 1. 验签
        String appId = request.getHeader("appId");
        String sign = request.getHeader("sign");
        String verifyResult = signVerifier.verify(appId, sign, body);
        if (verifyResult != null) {
            log.warn("[货主入站] 验签失败: {}", verifyResult);
            return ResponseEntity.status(401).body(Map.of(
                    "code", 401,
                    "success", false,
                    "msg", verifyResult
            ));
        }

        // 2. 解析并创建工单
        try {
            JsonNode root = objectMapper.readTree(body);

            String conversationId = root.path("conversationId").asText(null);
            String conversationTitle = root.path("conversationTitle").asText("");
            String senderNick = root.path("senderNick").asText("");
            String senderStaffId = root.path("senderStaffId").asText(null);
            String content = root.path("text").path("content").asText("");

            if (content.isBlank()) {
                return ResponseEntity.badRequest().body(Map.of(
                        "code", 400,
                        "success", false,
                        "msg", "消息内容为空"
                ));
            }

            // 3. 校验 senderStaffId 必填
            if (senderStaffId == null || senderStaffId.isBlank()) {
                log.warn("[货主入站] senderStaffId 缺失");
                return ResponseEntity.badRequest().body(Map.of(
                        "code", 400,
                        "success", false,
                        "msg", "senderStaffId 缺失，无法关联系统用户"
                ));
            }

            // 4. 通过 externalUserId 查找关联的系统用户
            SysUser submitter = userService.getByExternalUserIdIgnoreTenant(senderStaffId);
            if (submitter == null) {
                log.warn("[货主入站] 未找到关联的系统用户 externalUserId={}", senderStaffId);
                return ResponseEntity.badRequest().body(Map.of(
                        "code", 400,
                        "success", false,
                        "msg", "未找到外部用户ID对应的系统用户: " + senderStaffId
                ));
            }

            // 截断超长输入
            if (content.length() > MAX_CONTENT_LENGTH) {
                content = content.substring(0, MAX_CONTENT_LENGTH);
            }

            log.info("[货主入站] 验签通过，收到消息 conversationId={} senderNick={} senderStaffId={} contentPreview={}",
                    conversationId, senderNick, senderStaffId,
                    content.length() > 50 ? content.substring(0, 50) + "..." : content);

            // 5. 调用 AI 解析消息内容
            String aiResult = aiParseService.parse(content);
            JsonNode parsed = objectMapper.readTree(aiResult);

            String title = parsed.path("title").asText("工单");
            String description = parsed.path("description").asText(content);
            String trackingNo = parsed.path("trackingNo").asText(null);
            String targetAddress = parsed.path("targetAddress").asText(null);
            String typeStr = parsed.path("type").asText("OTHER");

            WorkOrderType type;
            try {
                type = WorkOrderType.valueOf(typeStr);
            } catch (IllegalArgumentException e) {
                type = WorkOrderType.OTHER;
            }

            // 6. 创建工单
            WorkOrder order = new WorkOrder();
            order.setTitle(title);
            order.setDescription(description);
            order.setTrackingNo(trackingNo);
            order.setTargetAddress(targetAddress);
            order.setType(type);
            order.setPriority(1);
            order.setStatus(WorkOrderStatus.PENDING);
            order.setConversationId(conversationId);
            order.setSenderStaffId(senderStaffId);
            order.setSubmitterId(submitter.getId());
            // Webhook 无 Sa-Token session，TenantContext 未设置，租户拦截器默认注入 0
            // 显式继承映射系统用户的租户 ID，确保工单落到正确租户
            order.setTenantId(submitter.getTenantId());
            TenantContext.setTenantId(submitter.getTenantId());

            WorkOrder created = workOrderService.create(order);

            log.info("[货主入站] 工单创建成功 orderId={} type={} trackingNo={}",
                    created.getId(), type, trackingNo);

            return ResponseEntity.ok(Map.of(
                    "code", 200,
                    "success", true,
                    "msg", "OK",
                    "data", Map.of("workOrderId", created.getId().toString())
            ));

        } catch (AiParseService.AiParseException e) {
            log.warn("[货主入站] AI 解析失败: {}", e.getMessage());
            // AI 解析失败，同步返回具体错误原因给货主侧
            return ResponseEntity.badRequest().body(Map.of(
                    "code", 400,
                    "success", false,
                    "msg", "AI 解析失败: " + e.getMessage()
            ));
        } catch (Exception e) {
            log.error("[货主入站] 消息处理失败", e);
            return ResponseEntity.internalServerError().body(Map.of(
                    "code", 500,
                    "success", false,
                    "msg", "系统处理异常: " + e.getMessage()
            ));
        }
    }
}
