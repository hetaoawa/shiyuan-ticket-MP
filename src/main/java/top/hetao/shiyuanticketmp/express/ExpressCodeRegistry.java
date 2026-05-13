package top.hetao.shiyuanticketmp.express;

import org.springframework.stereotype.Component;

import java.util.*;
import java.util.regex.Pattern;

/**
 * 快递公司编码注册表。
 *
 * <p>根据单号前缀规则自动识别快递公司编码（cpCode）。
 * <p>采用前缀长度降序匹配 + 单号总长度约束消解歧义。
 */
@Component
public class ExpressCodeRegistry {

    /**
     * 快递公司编码映射：编码 -> 中文名称
     */
    private static final Map<String, String> CODE_MAP = Map.ofEntries(
            Map.entry("ZTO", "中通快递"),
            Map.entry("YTO", "圆通速递"),
            Map.entry("YD", "韵达速递"),
            Map.entry("STO", "申通快递"),
            Map.entry("SF", "顺丰速运"),
            Map.entry("JD", "京东快递"),
            Map.entry("YZPY", "邮政快递包裹"),
            Map.entry("EMS", "EMS"),
            Map.entry("JTSD", "极兔速递"),
            Map.entry("DBL", "德邦快递"),
            Map.entry("FWX", "丰网速运"),
            Map.entry("HTKY", "百世快递"),
            Map.entry("DBKD", "德邦快递"),
            Map.entry("DBKY", "德邦快运"),
            Map.entry("ZTOKY", "中通快运"),
            Map.entry("YDKY", "韵达快运"),
            Map.entry("JDKY", "京东快运"),
            Map.entry("STOKY", "申通快运"),
            Map.entry("ZTKY", "中铁物流/飞豹快递"),
            Map.entry("KYE", "跨越速运"),
            Map.entry("KYSY", "跨越速运"),
            Map.entry("SHUNFENGKUAIYUN", "顺丰快运")
    );

    /**
     * 前缀规则：prefix -> {code, minLen, maxLen}
     * <p>通过单号总长度约束消解相同前缀在不同快递公司间的歧义。
     * <p>按前缀长度降序排列，匹配时优先尝试较长前缀。
     */
    private static final List<PrefixRule> PREFIX_RULES = List.of(
            // === 3位前缀（最优先） ===
            // 申通：268/368/468/568/668/768/868/968 开头 12~15位
            new PrefixRule("268", "STO", 12, 15),
            new PrefixRule("368", "STO", 12, 15),
            new PrefixRule("468", "STO", 12, 15),
            new PrefixRule("568", "STO", 12, 15),
            new PrefixRule("668", "STO", 12, 15),
            new PrefixRule("768", "STO", 12, 15),
            new PrefixRule("868", "STO", 12, 15),
            new PrefixRule("968", "STO", 12, 15),
            // 德邦：DPK 开头 12~15位
            new PrefixRule("DPK", "DBL", 12, 15),

            // === 2位字母前缀 ===
            // 顺丰：SF + 12~15位数字
            new PrefixRule("SF", "SF", 12, 15),
            // 圆通：YT + 13~18位数字
            new PrefixRule("YT", "YTO", 13, 18),
            // 极兔：JT + 13位数字
            new PrefixRule("JT", "JTSD", 13, 15),
            // 德邦：DB + 9~12位
            new PrefixRule("DB", "DBL", 9, 15),
            // 京东：JD + 10~13位数字
            new PrefixRule("JD", "JD", 10, 15),
            // 丰网：FW + 12~15位
            new PrefixRule("FW", "FWX", 12, 15),
            // 跨越：KY + 10~12位
            new PrefixRule("KY", "KYE", 10, 14),

            // === 2位数字前缀（需长度消歧） ===
            // 中通：78/76/75/68/61/88/80/53/20/30 开头 12~14位
            new PrefixRule("78", "ZTO", 12, 14),
            new PrefixRule("76", "ZTO", 12, 14),
            new PrefixRule("75", "ZTO", 12, 14),
            new PrefixRule("68", "ZTO", 12, 14),
            new PrefixRule("61", "ZTO", 12, 14),
            new PrefixRule("88", "ZTO", 12, 14),
            new PrefixRule("80", "ZTO", 12, 14),
            new PrefixRule("53", "ZTO", 12, 14),
            new PrefixRule("20", "ZTO", 12, 14),
            new PrefixRule("30", "ZTO", 12, 14),
            // 韵达：31/46/43/12~19 开头 13位
            new PrefixRule("31", "YD", 13, 13),
            new PrefixRule("46", "YD", 13, 13),
            new PrefixRule("43", "YD", 13, 13),
            new PrefixRule("12", "YD", 13, 13),
            new PrefixRule("13", "YD", 13, 13),
            new PrefixRule("14", "YD", 13, 13),
            new PrefixRule("15", "YD", 13, 13),
            new PrefixRule("16", "YD", 13, 13),
            new PrefixRule("17", "YD", 13, 13),
            new PrefixRule("18", "YD", 13, 13),
            new PrefixRule("19", "YD", 13, 13),
            // 申通：77 开头 12~15位
            new PrefixRule("77", "STO", 12, 15),
            // 百世：70~74 开头 12~14位
            new PrefixRule("70", "HTKY", 12, 14),
            new PrefixRule("71", "HTKY", 12, 14),
            new PrefixRule("72", "HTKY", 12, 14),
            new PrefixRule("73", "HTKY", 12, 14),
            new PrefixRule("74", "HTKY", 12, 14),
            // 邮政：99~91 开头 11~13位
            new PrefixRule("99", "YZPY", 11, 13),
            new PrefixRule("98", "YZPY", 11, 13),
            new PrefixRule("97", "YZPY", 11, 13),
            new PrefixRule("96", "YZPY", 11, 13),
            new PrefixRule("95", "YZPY", 11, 13),
            new PrefixRule("94", "YZPY", 11, 13),
            new PrefixRule("93", "YZPY", 11, 13),
            new PrefixRule("92", "YZPY", 11, 13),
            new PrefixRule("91", "YZPY", 11, 13),
            // 中通 90 开头 12~14位（与邮政 90 开头 11~13 位通过长度区分）
            new PrefixRule("90", "ZTO", 12, 14),
            // 邮政 90 开头 11~13位（较短单号归邮政）
            new PrefixRule("90", "YZPY", 11, 13),

            // === 单字母前缀（最低优先级） ===
            // EMS：E + 9~13位数字 + CS/CN（特殊格式，用正则兜底）
            new PrefixRule("E", "EMS", 11, 15)
    );

    /**
     * EMS 正则：E + 9~13位数字 + CS/CN 结尾
     */
    private static final Pattern EMS_PATTERN = Pattern.compile("^E\\d{9,13}(CS|CN)$");

    /**
     * 需要手机号后四位的快递公司编码集合
     */
    private static final Set<String> MOBILE_REQUIRED_CODES = Set.of("SF", "ZTO", "SHUNFENGKUAIYUN");

    /**
     * 根据单号识别快递公司编码。
     *
     * @param trackingNo 物流单号
     * @return 快递公司编码，无法识别时返回 null
     */
    public String identify(String trackingNo) {
        if (trackingNo == null || trackingNo.isBlank()) {
            return null;
        }

        String no = trackingNo.trim().toUpperCase();

        // 按前缀长度降序匹配（3位 > 2位 > 1位），首个命中的规则即为结果
        for (PrefixRule rule : PREFIX_RULES) {
            if (no.startsWith(rule.prefix) && no.length() >= rule.minLen && no.length() <= rule.maxLen) {
                // EMS 特殊校验：E 开头需匹配 E+数字+CS/CN 格式
                if ("EMS".equals(rule.code) && rule.prefix.length() == 1) {
                    if (EMS_PATTERN.matcher(no).matches()) {
                        return rule.code;
                    }
                    continue;
                }
                return rule.code;
            }
        }

        return null;
    }

    /**
     * 判断指定快递公司是否需要手机号后四位。
     *
     * @param cpCode 快递公司编码
     * @return true=需要，false=不需要
     */
    public boolean isMobileRequired(String cpCode) {
        return MOBILE_REQUIRED_CODES.contains(cpCode);
    }

    /**
     * 获取快递公司中文名称。
     *
     * @param cpCode 快递公司编码
     * @return 中文名称，未知编码返回编码本身
     */
    public String getCompanyName(String cpCode) {
        return CODE_MAP.getOrDefault(cpCode, cpCode);
    }

    /**
     * 获取所有支持的快递公司编码。
     *
     * @return 编码集合
     */
    public Set<String> getAllCodes() {
        return CODE_MAP.keySet();
    }

    /**
     * 前缀规则
     */
    private static class PrefixRule {
        final String prefix;
        final String code;
        final int minLen;
        final int maxLen;

        PrefixRule(String prefix, String code, int minLen, int maxLen) {
            this.prefix = prefix;
            this.code = code;
            this.minLen = minLen;
            this.maxLen = maxLen;
        }
    }
}
