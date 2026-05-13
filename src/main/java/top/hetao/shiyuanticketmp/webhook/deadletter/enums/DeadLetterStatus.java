package top.hetao.shiyuanticketmp.webhook.deadletter.enums;

/**
 * 死信记录处理状态枚举
 */
public enum DeadLetterStatus {
    /** 待管理员处理 */
    PENDING,
    /** 管理员已触发重新投递成功 */
    RESOLVED,
    /** 管理员确认忽略，不再重试 */
    IGNORED
}
