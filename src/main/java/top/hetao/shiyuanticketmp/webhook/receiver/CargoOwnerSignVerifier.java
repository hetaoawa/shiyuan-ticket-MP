package top.hetao.shiyuanticketmp.webhook.receiver;

import cn.hutool.crypto.asymmetric.KeyType;
import cn.hutool.crypto.asymmetric.RSA;
import cn.hutool.crypto.digest.DigestUtil;
import cn.hutool.json.JSONUtil;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;

/**
 * 货主侧 WebHook 签名验证器。
 *
 * <p>验签流程（与货主侧签名流程对应）：
 * <ol>
 *   <li>货主侧：请求体 JSON → MD5 摘要 → RSA 公钥加密 → Base64 编码 → sign 请求头</li>
 *   <li>本系统：sign → Base64 解码 → RSA 私钥解密 → 得到摘要 → 与本端 MD5(body) 比对</li>
 * </ol>
 *
 * <p>私钥为 PEM 格式（PKCS#8），配置在 {@code webhook.cargo-owner.receive.private-key} 中。
 * 环境变量值为 PEM 内容的 Base64 编码（双重编码），解析时自动处理。
 */
@Component
public class CargoOwnerSignVerifier {

    private static final Logger log = LoggerFactory.getLogger(CargoOwnerSignVerifier.class);

    @Value("${webhook.cargo-owner.receive.app-id:}")
    private String expectedAppId;

    @Value("${webhook.cargo-owner.receive.private-key:}")
    private String privateKeyPem;

    ObjectMapper objectMapper = new ObjectMapper();

    /**
     * 验证签名。
     *
     * @param appId 请求头中的应用编码
     * @param sign  请求头中的签名（Base64 编码）
     * @param body  原始请求体 JSON 字符串
     * @return null 表示验证通过，非 null 为错误信息
     */
    public String verify(String appId, String sign, String body) {
        // 1. 校验 appId
        if (expectedAppId != null && !expectedAppId.isBlank()) {
            if (appId == null || !appId.equals(expectedAppId)) {
                return "appId 不匹配";
            }
        }

        // 2. 校验 sign 非空
        if (sign == null || sign.isBlank()) {
            return "sign 请求头缺失";
        }

        // 3. 校验私钥已配置
        if (privateKeyPem == null || privateKeyPem.isBlank()) {
            log.error("[货主验签] 私钥未配置，请设置 webhook.cargo-owner.private-key");
            return "服务端验签配置错误";
        }

        try {
            String json = JSONUtil.toJsonStr(JSONUtil.parse(body));

            String md5 = DigestUtil.md5Hex(json);

            RSA rsa = new RSA(privateKeyPem, null);
            String decryptMd5 =  rsa.decryptStr(sign, KeyType.PrivateKey, StandardCharsets.UTF_8);

            // 6. 比对两端 MD5
            if (!decryptMd5.equalsIgnoreCase(md5)) {
                log.warn("[货主验签] MD5 不匹配 local={} remote={}", decryptMd5, md5);
                return "签名验证失败";
            }

            return null; // 验证通过
        } catch (Exception e) {
            log.error("[货主验签] 验签过程异常", e);
            return "验签过程异常: " + e.getMessage();
        }
    }
}
