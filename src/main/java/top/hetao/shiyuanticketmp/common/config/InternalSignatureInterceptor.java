package top.hetao.shiyuanticketmp.common.config;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.format.DateTimeParseException;

/**
 * 前端请求签名校验拦截器。
 *
 * <p>要求 /api/** 路径（排除 /api/webhook 和 /api/webhook/**）携带：
 * <ul>
 *   <li>X-Internal-Signature：固定值，与配置 internal.signature.value 常量时间比较</li>
 *   <li>X-Internal-Timestamp：ISO8601 时间戳，与后端系统时间差在容忍秒数内</li>
 * </ul>
 */
@Component
public class InternalSignatureInterceptor implements HandlerInterceptor {

    private static final String HEADER_SIGNATURE = "X-Internal-Signature";
    private static final String HEADER_TIMESTAMP = "X-Internal-Timestamp";

    @Value("${internal.signature.value}")
    private String expectedSignature;

    @Value("${internal.timestamp.tolerance-seconds:300}")
    private long toleranceSeconds;

    @Override
    public boolean preHandle(HttpServletRequest request,
                             HttpServletResponse response,
                             Object handler) throws Exception {
        String path = request.getRequestURI();

        // 排除 webhook 路径
        if (path.equals("/api/webhook") || path.startsWith("/api/webhook/")) {
            return true;
        }

        String signature = request.getHeader(HEADER_SIGNATURE);
        String timestamp = request.getHeader(HEADER_TIMESTAMP);

        // 缺少任一头部则拒绝
        if (signature == null || timestamp == null) {
            response.setStatus(HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
            response.setContentType("application/json;charset=UTF-8");
            response.getWriter().write("{\"code\":500,\"message\":\"Missing required headers\"}");
            return false;
        }

        // 校验时间戳格式和有效期
        Instant requestTime;
        try {
            requestTime = Instant.parse(timestamp);
        } catch (DateTimeParseException e) {
            response.setStatus(HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
            response.setContentType("application/json;charset=UTF-8");
            response.getWriter().write("{\"code\":500,\"message\":\"Invalid timestamp format\"}");
            return false;
        }

        Instant now = Instant.now();
        long diffSeconds = Math.abs(now.getEpochSecond() - requestTime.getEpochSecond());
        if (diffSeconds > toleranceSeconds) {
            response.setStatus(HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
            response.setContentType("application/json;charset=UTF-8");
            response.getWriter().write("{\"code\":500,\"message\":\"Timestamp expired\"}");
            return false;
        }

        // 常量时间比较签名
        if (!constantTimeEquals(expectedSignature, signature)) {
            response.setStatus(HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
            response.setContentType("application/json;charset=UTF-8");
            response.getWriter().write("{\"code\":500,\"message\":\"Invalid signature\"}");
            return false;
        }

        return true;
    }

    /**
     * 常量时间字符串比较，防止时序攻击。
     */
    private boolean constantTimeEquals(String a, String b) {
        if (a == null || b == null) {
            return false;
        }
        byte[] aBytes = a.getBytes(StandardCharsets.UTF_8);
        byte[] bBytes = b.getBytes(StandardCharsets.UTF_8);
        return MessageDigest.isEqual(aBytes, bBytes);
    }
}
