package top.hetao.shiyuanticketmp.webhook.sender;

/**
 * WebHook 投递通道目标。
 *
 * <p>标记事件应发往哪个通道，由业务层根据规则设置。
 */
public enum ChannelTarget {
    /** 仅钉钉（云仓侧） */
    DINGTALK,
    /** 仅货主侧 */
    CARGO_OWNER,
    /** 双通道 */
    BOTH
}
