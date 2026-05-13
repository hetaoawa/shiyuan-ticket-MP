package top.hetao.shiyuanticketmp.util;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

/**
 * HMAC-SHA256 签名工具类
 *
 * <p>用于生成 WebHook 请求头中的签名，保证接收方能够验证请求来源合法性。
 * 签名算法：HMAC-SHA256(payload_bytes, sharedSecret) → Base64 编码字符串
 *
 * <p>接收方验签流程：
 * <ol>
 *   <li>从请求头取出 {@code X-Signature} 字段</li>
 *   <li>用同一 sharedSecret 对请求体重新计算签名</li>
 *   <li>与请求头签名做等时比较（{@link #safeEquals}），防止时序攻击</li>
 * </ol>
 */
public final class HMACUtils {

    private static final String ALGORITHM = "HmacSHA256";

    private HMACUtils() {}

    /**
     * 对原始 payload 字节用共享密钥计算 HMAC-SHA256，并以 Base64 返回。
     *
     * @param payload      请求体原始字节（UTF-8 编码的 JSON 字符串）
     * @param sharedSecret 与接收方约定的共享密钥（建议 ≥ 32 字节随机串）
     * @return Base64 编码的签名字符串，写入请求头 {@code X-Signature}
     * @throws IllegalStateException 签名计算失败时（正常环境不会发生）
     */
    public static String sign(byte[] payload, String sharedSecret) {
        try {
            Mac mac = Mac.getInstance(ALGORITHM);
            SecretKeySpec keySpec = new SecretKeySpec(
                    sharedSecret.getBytes(StandardCharsets.UTF_8), ALGORITHM);
            mac.init(keySpec);
            byte[] rawHmac = mac.doFinal(payload);
            return Base64.getEncoder().encodeToString(rawHmac);
        } catch (Exception e) {
            throw new IllegalStateException("HMAC-SHA256 签名失败", e);
        }
    }

    /**
     * 等时字符串比较，防止时序攻击（Timing Attack）。
     *
     * <p>普通 {@code String.equals()} 在字符串首位不匹配时立即返回，
     * 攻击者可通过响应时间差推断签名内容。本方法确保无论何处不匹配，
     * 比较时间恒定。
     *
     * @param a 服务端重新计算的签名
     * @param b 请求头携带的签名
     * @return 两者内容完全相同返回 {@code true}
     */
    public static boolean safeEquals(String a, String b) {
        if (a == null || b == null) return false;
        byte[] aBytes = a.getBytes(StandardCharsets.UTF_8);
        byte[] bBytes = b.getBytes(StandardCharsets.UTF_8);
        if (aBytes.length != bBytes.length) return false;
        int result = 0;
        for (int i = 0; i < aBytes.length; i++) {
            result |= aBytes[i] ^ bBytes[i]; // 累积差异位，不提前退出
        }
        return result == 0;
    }
}
