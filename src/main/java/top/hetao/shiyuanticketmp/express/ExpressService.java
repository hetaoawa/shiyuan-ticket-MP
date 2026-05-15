package top.hetao.shiyuanticketmp.express;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import top.hetao.shiyuanticketmp.express.dto.ExpressTraceResponse;
import top.hetao.shiyuanticketmp.express.entity.ExpressTraceRecord;
import top.hetao.shiyuanticketmp.express.mapper.ExpressTraceMapper;
import top.hetao.shiyuanticketmp.workorder.exception.WorkOrderException;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.concurrent.TimeUnit;

/**
 * 物流轨迹查询服务。
 *
 * <p>调用阿里云市场快递查询接口（POST）获取物流轨迹。
 * 支持 DB 落库缓存（12 小时有效期，已签收永不过期）。
 */
@Service
public class ExpressService {

    private static final Logger log = LoggerFactory.getLogger(ExpressService.class);

    private static final String API_URL = "https://kzexpress.market.alicloudapi.com/api-mall/api/express/query";

    /** 需要手机号后四位的快递公司默认值 */
    private static final String DEFAULT_MOBILE_LAST4 = "7426";

    /** DB 缓存有效期（小时）：正常状态 */
    private static final int CACHE_HOURS = 12;

    /** DB 缓存有效期（分钟）：无物流/待揽收/已揽收状态 */
    private static final int CACHE_MINUTES_SHORT = 10;

    /** 已签收状态码 */
    private static final String STATUS_DELIVERED = "DELIVERED";

    /** 短缓存的状态：待揽收/已揽收（ACCEPT） */
    private static final String STATUS_ACCEPT = "ACCEPT";

    /** Redis 缓存 key 前缀 */
    private static final String REDIS_CACHE_PREFIX = "express:trace:";

    /** Redis 缓存 TTL（秒）：5 分钟 */
    private static final int REDIS_CACHE_TTL_SECONDS = 300;

    @Value("${express.appcode}")
    private String appcode;

    private final ExpressCodeRegistry codeRegistry;
    private final ExpressTraceMapper traceMapper;
    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;

    public ExpressService(ExpressCodeRegistry codeRegistry, ExpressTraceMapper traceMapper,
                          StringRedisTemplate redisTemplate, ObjectMapper objectMapper) {
        this.codeRegistry = codeRegistry;
        this.traceMapper = traceMapper;
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;
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
     * 查询物流轨迹（强制刷新，不写 DB）。
     *
     * <p>若需要手机号后四位但未提供，静默使用默认值 7426。
     *
     * @param trackingNo  物流单号
     * @param mobileLast4 收件手机号后四位（可选）
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

        // 2. 处理手机号后四位（需要时静默使用默认值）
        String effectiveMobile = mobileLast4;
        if (codeRegistry.isMobileRequired(resolvedCpCode)) {
            if (effectiveMobile == null || effectiveMobile.isBlank()) {
                effectiveMobile = DEFAULT_MOBILE_LAST4;
                log.info("[物流查询] 使用默认手机号后四位 trackingNo={}", trackingNo);
            }
            if (!effectiveMobile.matches("^\\d{4}$")) {
                throw new WorkOrderException("手机号后四位格式错误，必须为4位数字");
            }
        }

        // 3. 调用外部接口（POST）
        return doQueryExternal(trackingNo, effectiveMobile, resolvedCpCode);
    }

    /**
     * 查询物流轨迹并落库（工单完结时调用）。
     *
     * <p>使用 5 分钟 Redis 缓存（如有），获取后落库存储。
     */
    public ExpressTraceResponse queryAndSaveTrace(String trackingNo, String mobileLast4, String cpCode) {
        ExpressTraceResponse trace = queryTrace(trackingNo, mobileLast4, cpCode);
        saveToDb(trackingNo, trace);
        return trace;
    }

    /**
     * 工单完结时获取物流信息并落库。
     *
     * <p>逻辑：
     * <ol>
     *   <li>先查 Redis 缓存（5 分钟），命中则直接使用</li>
     *   <li>未命中则调用外部接口获取</li>
     *   <li>获取后落库存储到 express_trace 表</li>
     *   <li>同时更新 Redis 缓存</li>
     * </ol>
     *
     * @param trackingNo 物流单号
     */
    public void fetchAndSaveOnClose(String trackingNo) {
        if (trackingNo == null || trackingNo.isBlank()) {
            return;
        }

        try {
            ExpressTraceResponse trace = null;

            // 1. 先查 Redis 缓存
            String cacheKey = REDIS_CACHE_PREFIX + trackingNo;
            String cached = redisTemplate.opsForValue().get(cacheKey);
            if (cached != null) {
                log.info("[物流查询] 工单完结命中 Redis 缓存 trackingNo={}", trackingNo);
                try {
                    trace = objectMapper.readValue(cached, ExpressTraceResponse.class);
                } catch (JsonProcessingException e) {
                    log.warn("[物流查询] Redis 缓存反序列化失败，重新查询 trackingNo={}", trackingNo);
                    trace = null;
                }
            }

            // 2. Redis 未命中，调用外部接口
            if (trace == null) {
                trace = queryTrace(trackingNo, null, null);
                // 更新 Redis 缓存
                try {
                    String json = objectMapper.writeValueAsString(trace);
                    redisTemplate.opsForValue().set(cacheKey, json, REDIS_CACHE_TTL_SECONDS, TimeUnit.SECONDS);
                } catch (JsonProcessingException e) {
                    log.warn("[物流查询] Redis 缓存写入失败 trackingNo={}", trackingNo, e);
                }
            }

            // 3. 落库
            saveToDb(trackingNo, trace);
            log.info("[物流查询] 工单完结物流落库完成 trackingNo={} status={}", trackingNo, trace.getLogisticsStatus());

        } catch (Exception e) {
            // 物流查询失败不阻塞工单关闭流程
            log.error("[物流查询] 工单完结物流获取失败 trackingNo={}", trackingNo, e);
        }
    }

    /**
     * 获取存储的物流信息（DB 缓存优先）。
     *
     * <p>逻辑：
     * <ol>
     *   <li>DB 中有数据且未过期 → 直接返回</li>
     *   <li>DB 中有数据但已过期（已签收永不过期）→ 重新获取并更新</li>
     *   <li>DB 中无数据 → 调用外部接口获取</li>
     *   <li>获取到的物流状态为无物流/待揽收/已揽收 → 不落库，直接返回</li>
     *   <li>获取到的物流状态有轨迹或非 ACCEPT → 落库（12h 有效期）</li>
     *   <li>获取到的物流状态为已签收 → 落库（永不过期）</li>
     * </ol>
     *
     * @param trackingNo  物流单号
     * @param mobileLast4 手机号后四位（可选）
     * @param cpCode      快递公司编码（可选）
     * @return 物流轨迹响应
     */
    public ExpressTraceResponse getTrackedTrace(String trackingNo, String mobileLast4, String cpCode) {
        // 1. 查 DB
        ExpressTraceRecord record = getByTrackingNo(trackingNo);

        if (record != null) {
            // 检查是否过期
            if (!isExpired(record)) {
                log.info("[物流查询] DB 缓存命中 trackingNo={} status={} expiresAt={}",
                        trackingNo, record.getLogisticsStatus(), record.getExpiresAt());
                return deserialize(record.getResponseJson());
            }
            // 已过期，继续重新获取
            log.info("[物流查询] DB 缓存已过期 trackingNo={}", trackingNo);
        }

        // 2. 调用外部接口
        ExpressTraceResponse trace = queryTrace(trackingNo, mobileLast4, cpCode);

        // 3. 落库（所有状态均落库，不同时长）
        saveToDb(trackingNo, trace);

        return trace;
    }

    // ----------------------------------------------------------------
    // DB 操作
    // ----------------------------------------------------------------

    /**
     * 按物流单号查询 DB 记录。
     */
    private ExpressTraceRecord getByTrackingNo(String trackingNo) {
        return traceMapper.selectOne(new LambdaQueryWrapper<ExpressTraceRecord>()
                .eq(ExpressTraceRecord::getTrackingNo, trackingNo));
    }

    /**
     * 检查记录是否过期。
     *
     * <p>已签收（expiresAt == null）永不过期。
     */
    private boolean isExpired(ExpressTraceRecord record) {
        if (record.getExpiresAt() == null) {
            return false; // 已签收，永不过期
        }
        return LocalDateTime.now().isAfter(record.getExpiresAt());
    }

    /**
     * 判断物流状态是否应该落库。
     *
     * <p>所有状态均落库，但不同状态使用不同的缓存时长：
     * <ul>
     *   <li>已签收（DELIVERED）：永不过期</li>
     *   <li>无物流/待揽收/已揽收（ACCEPT）：10 分钟</li>
     *   <li>其他状态：12 小时</li>
     * </ul>
     */
    private boolean shouldSaveToDb(ExpressTraceResponse trace) {
        return trace != null && trace.getLogisticsStatus() != null;
    }

    /**
     * 保存或更新物流轨迹到 DB。
     *
     * <p>使用先查后写 + 重复键异常兜底，防止并发写入时竞态条件导致 DuplicateKeyException。
     */
    private void saveToDb(String trackingNo, ExpressTraceResponse trace) {
        try {
            String responseJson = objectMapper.writeValueAsString(trace);
            String status = trace.getLogisticsStatus();
            LocalDateTime expiresAt = calcExpiresAt(status);

            ExpressTraceRecord existing = getByTrackingNo(trackingNo);
            if (existing != null) {
                updateRecord(existing, trace, responseJson, expiresAt);
            } else {
                insertRecord(trackingNo, trace, responseJson, expiresAt);
            }
        } catch (Exception e) {
            log.error("[物流查询] DB 落库失败 trackingNo={}", trackingNo, e);
        }
    }

    /**
     * 插入记录，若发生重复键异常则降级为更新。
     */
    private void insertRecord(String trackingNo, ExpressTraceResponse trace,
                               String responseJson, LocalDateTime expiresAt) {
        try {
            ExpressTraceRecord record = new ExpressTraceRecord();
            record.setTrackingNo(trackingNo);
            record.setCpCode(trace.getCpCode());
            record.setLogisticsStatus(trace.getLogisticsStatus());
            record.setLogisticsStatusDesc(trace.getLogisticsStatusDesc());
            record.setResponseJson(responseJson);
            record.setFetchedAt(LocalDateTime.now());
            record.setExpiresAt(expiresAt);
            traceMapper.insert(record);
            log.info("[物流查询] DB 新增 trackingNo={} status={} expiresAt={}",
                    trackingNo, trace.getLogisticsStatus(), expiresAt);
        } catch (org.springframework.dao.DuplicateKeyException e) {
            // 并发写入竞态：另一个线程已插入，降级为更新
            log.warn("[物流查询] 重复键冲突，降级为更新 trackingNo={}", trackingNo);
            ExpressTraceRecord existing = getByTrackingNo(trackingNo);
            if (existing != null) {
                updateRecord(existing, trace, responseJson, expiresAt);
            }
        }
    }

    /**
     * 更新已有记录。
     */
    private void updateRecord(ExpressTraceRecord existing, ExpressTraceResponse trace,
                               String responseJson, LocalDateTime expiresAt) {
        existing.setCpCode(trace.getCpCode());
        existing.setLogisticsStatus(trace.getLogisticsStatus());
        existing.setLogisticsStatusDesc(trace.getLogisticsStatusDesc());
        existing.setResponseJson(responseJson);
        existing.setFetchedAt(LocalDateTime.now());
        existing.setExpiresAt(expiresAt);
        traceMapper.updateById(existing);
        log.info("[物流查询] DB 更新 trackingNo={} status={} expiresAt={}",
                existing.getTrackingNo(), trace.getLogisticsStatus(), expiresAt);
    }

    /**
     * 根据物流状态计算过期时间。
     */
    private LocalDateTime calcExpiresAt(String status) {
        if (STATUS_DELIVERED.equals(status)) {
            return null; // 已签收永不过期
        } else if (STATUS_ACCEPT.equals(status)) {
            return LocalDateTime.now().plusMinutes(CACHE_MINUTES_SHORT);
        } else {
            return LocalDateTime.now().plusHours(CACHE_HOURS);
        }
    }

    // ----------------------------------------------------------------
    // 外部接口调用
    // ----------------------------------------------------------------

    /**
     * 调用外部物流查询接口。
     */
    private ExpressTraceResponse doQueryExternal(String trackingNo, String mobileLast4, String cpCode) {
        try {
            String formBody = buildFormBody(trackingNo, mobileLast4, cpCode);
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(API_URL))
                    .header("Authorization", "APPCODE " + appcode)
                    .header("Content-Type", "application/x-www-form-urlencoded")
                    .POST(HttpRequest.BodyPublishers.ofString(formBody, StandardCharsets.UTF_8))
                    .timeout(Duration.ofSeconds(15))
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));

            if (response.statusCode() != 200) {
                log.error("[物流查询] 外部接口返回异常 status={} body={}", response.statusCode(), response.body());
                throw new WorkOrderException("物流查询接口异常，请稍后重试");
            }

            return parseResponse(response.body(), trackingNo, cpCode);

        } catch (WorkOrderException e) {
            throw e;
        } catch (Exception e) {
            log.error("[物流查询] 调用外部接口失败 trackingNo={}", trackingNo, e);
            throw new WorkOrderException("物流查询失败：" + e.getMessage());
        }
    }

    private String buildFormBody(String trackingNo, String mobileLast4, String cpCode) {
        StringBuilder sb = new StringBuilder();
        sb.append("expressNo=").append(trackingNo);
        sb.append("&cpCode=").append(cpCode);
        if (mobileLast4 != null && !mobileLast4.isBlank()) {
            sb.append("&mobile=").append(mobileLast4);
        }
        return sb.toString();
    }

    private ExpressTraceResponse parseResponse(String body, String trackingNo, String cpCode) {
        try {
            JsonNode root = objectMapper.readTree(body);

            boolean success = root.path("success").asBoolean(false);
            int code = root.path("code").asInt(0);

            if (!success || code != 200) {
                String msg = root.path("msg").asText("未知错误");
                log.warn("[物流查询] 接口返回失败 code={} msg={}", code, msg);
                throw new WorkOrderException("物流查询失败：" + msg);
            }

            JsonNode data = root.path("data");
            if (data.isMissingNode() || data.isNull()) {
                throw new WorkOrderException("物流查询返回数据为空");
            }

            return objectMapper.convertValue(data, new TypeReference<>() {});

        } catch (WorkOrderException e) {
            throw e;
        } catch (Exception e) {
            log.error("[物流查询] 解析响应失败 body={}", body, e);
            throw new WorkOrderException("解析物流信息失败");
        }
    }

    /**
     * 反序列化 JSON 到响应对象。
     */
    private ExpressTraceResponse deserialize(String json) {
        try {
            return objectMapper.readValue(json, ExpressTraceResponse.class);
        } catch (Exception e) {
            log.error("[物流查询] 反序列化失败", e);
            throw new WorkOrderException("物流数据解析失败");
        }
    }

    /**
     * 符合条件时落库（公开方法，供 Controller 调用）。
     *
     * <p>所有状态均落库，不同时长：已签收永不过期，ACCEPT 10分钟，其他 12 小时。
     */
    public void saveToDbIfQualified(String trackingNo, ExpressTraceResponse trace) {
        if (shouldSaveToDb(trace)) {
            saveToDb(trackingNo, trace);
        }
    }
}

