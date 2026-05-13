package top.hetao.shiyuanticketmp.common.context;

/**
 * 租户上下文持有器，基于 ThreadLocal 实现。
 *
 * <p>在用户登录时通过拦截器或 Sa-Token 的 StpLogic 将 tenantId 写入当前线程，
 * MyBatis-Plus 的 TenantLineInnerInterceptor 会自动从本类读取 tenantId 并拼接到 SQL 中。
 *
 * <p><b>使用方式：</b>
 * <pre>
 * // 登录成功后设置
 * TenantContext.setTenantId(user.getTenantId());
 *
 * // 请求结束时清理（由拦截器自动完成）
 * TenantContext.clear();
 * </pre>
 */
public final class TenantContext {

    private static final ThreadLocal<Long> TENANT_ID = new ThreadLocal<>();

    private TenantContext() {}

    /**
     * 获取当前线程绑定的租户 ID。
     *
     * @return 租户 ID，未设置时返回 null
     */
    public static Long getTenantId() {
        return TENANT_ID.get();
    }

    /**
     * 设置当前线程绑定的租户 ID。
     *
     * @param tenantId 租户 ID
     */
    public static void setTenantId(Long tenantId) {
        TENANT_ID.set(tenantId);
    }

    /**
     * 清除当前线程的租户上下文。
     * 在请求结束或登出时必须调用，防止 ThreadLocal 泄漏。
     */
    public static void clear() {
        TENANT_ID.remove();
    }
}
