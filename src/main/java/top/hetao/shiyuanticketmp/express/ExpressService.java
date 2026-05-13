package top.hetao.shiyuanticketmp.express;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import top.hetao.shiyuanticketmp.express.dto.ExpressTraceResponse;
import top.hetao.shiyuanticketmp.workorder.exception.WorkOrderException;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * 物流轨迹查询服务。
 *
 * <p>调用阿里云市场快递查询接口获取物流轨迹。
 */
@Service
public class ExpressService {

    private static final Logger log = LoggerFactory.getLogger(ExpressService.class);

    private static final String API_URL = "https://kzexpress.market.alicloudapi.com/api-mall/api/express/query";

    @Value("${express.appcode}")
    private String appcode;

    private final ExpressCodeRegistry codeRegistry;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;

    public ExpressService(ExpressCodeRegistry codeRegistry) {
        this.codeRegistry = codeRegistry;
        this.objectMapper = new ObjectMapper();
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
    }

    /**
     * 获取快递公司编码注册表。
     */
    public ExpressCodeRegistry getCodeRegistry() {
        return codeRegistry;
    }

    /**
     * 查询物流轨迹。
     *
     * @param trackingNo  物流单号
     * @param mobileLast4 收件手机号后四位（顺丰、中通必填）
     * @param cpCode      快递公司编码（可选，不传则自动识别）
     * @return 物流轨迹响应
     */
    public ExpressTraceResponse queryTrace(String trackingNo, String mobileLast4, String cpCode) {
        // 1. 识别快递公司
        String resolvedCpCode = cpCode;
        if (resolvedCpCode == null || resolvedCpCode.isBlank()) {
            resolvedCpCode = codeRegistry.identify(trackingNo);
            if (resolvedCpCode == null) {
                throw new WorkOrderException("无法识别快递公司，请手动指定快递公司编码（cpCode）");
            }
        }

        // 2. 检查是否需要手机号后四位
        if (codeRegistry.isMobileRequired(resolvedCpCode)) {
            if (mobileLast4 == null || mobileLast4.isBlank()) {
                String companyName = codeRegistry.getCompanyName(resolvedCpCode);
                throw new WorkOrderException(companyName + "需要提供收件手机号后四位");
            }
            if (!mobileLast4.matches("^\\d{4}$")) {
                throw new WorkOrderException("手机号后四位格式错误，必须为4位数字");
            }
        }

        // 3. 调用外部接口
        try {
            String url = buildUrl(trackingNo, mobileLast4, resolvedCpCode);
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .header("Authorization", "APPCODE " + appcode)
                    .GET()
                    .timeout(Duration.ofSeconds(15))
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() != 200) {
                log.error("[物流查询] 外部接口返回异常 status={} body={}", response.statusCode(), response.body());
                throw new WorkOrderException("物流查询接口异常，请稍后重试");
            }

            return parseResponse(response.body(), trackingNo, resolvedCpCode);

        } catch (WorkOrderException e) {
            throw e;
        } catch (Exception e) {
            log.error("[物流查询] 调用外部接口失败 trackingNo={}", trackingNo, e);
            throw new WorkOrderException("物流查询失败：" + e.getMessage());
        }
    }

    /**
     * 构建请求 URL。
     */
    private String buildUrl(String trackingNo, String mobileLast4, String cpCode) {
        StringBuilder sb = new StringBuilder(API_URL);
        sb.append("?expressNo=").append(trackingNo);
        sb.append("&cpCode=").append(cpCode);
        if (mobileLast4 != null && !mobileLast4.isBlank()) {
            sb.append("&mobile=").append(mobileLast4);
        }
        return sb.toString();
    }

    /**
     * 解析外部接口响应。
     */
    private ExpressTraceResponse parseResponse(String body, String trackingNo, String cpCode) {
        try {
            JsonNode root = objectMapper.readTree(body);

            ExpressTraceResponse response = new ExpressTraceResponse();
            response.setCpCode(cpCode);
            response.setCpName(codeRegistry.getCompanyName(cpCode));
            response.setTrackingNo(trackingNo);

            // 解析状态
            JsonNode statusNode = root.get("status");
            if (statusNode != null) {
                response.setStatus(statusNode.asInt());
                response.setStatusDesc(getStatusDesc(statusNode.asInt()));
            }

            // 解析轨迹
            JsonNode tracesNode = root.get("traces");
            if (tracesNode != null && tracesNode.isArray()) {
                List<ExpressTraceResponse.TraceNode> traces = new ArrayList<>();
                for (JsonNode traceNode : tracesNode) {
                    ExpressTraceResponse.TraceNode trace = new ExpressTraceResponse.TraceNode();
                    trace.setTime(traceNode.get("time").asText());
                    trace.setDescription(traceNode.get("desc").asText());
                    trace.setLocation(traceNode.has("area") ? traceNode.get("area").asText() : null);
                    traces.add(trace);
                }
                response.setTraces(traces);
            }

            return response;

        } catch (Exception e) {
            log.error("[物流查询] 解析响应失败 body={}", body, e);
            throw new WorkOrderException("解析物流信息失败");
        }
    }

    /**
     * 获取状态描述。
     */
    private String getStatusDesc(int status) {
        return switch (status) {
            case 0 -> "查询出错";
            case 1 -> "暂无记录";
            case 2 -> "在途中";
            case 3 -> "已签收";
            case 4 -> "问题件";
            default -> "未知状态";
        };
    }
}
