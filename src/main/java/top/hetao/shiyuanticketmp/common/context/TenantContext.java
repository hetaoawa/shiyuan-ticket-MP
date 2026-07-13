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
 * try (TenantContext.Scope ignored = TenantContext.useTenant(user.getTenantId())) {
 *     // 执行限定租户内的业务
 * }
 * </pre>
 */
public final class TenantContext {

    private static final ThreadLocal<State> STATE = new ThreadLocal<>();

    private TenantContext() {}

    /**
     * 获取当前线程绑定的租户 ID。
     *
     * @return 租户 ID，未设置时返回 null
     */
    public static Long getTenantId() {
        State state = STATE.get();
        return state == null ? null : state.tenantId();
    }

    /**
     * 获取当前线程绑定的租户 ID，缺失时立即失败，避免 SQL 静默落入平台租户。
     */
    public static Long requireTenantId() {
        Long tenantId = getTenantId();
        if (tenantId == null) {
            throw new IllegalStateException("当前线程未绑定租户");
        }
        return tenantId;
    }

    /**
     * 在最小作用域内绑定租户；关闭作用域时恢复进入前的完整上下文。
     */
    public static Scope useTenant(Long tenantId) {
        validateTenantId(tenantId);
        State previous = STATE.get();
        STATE.set(new State(tenantId, false));
        return new Scope(previous);
    }

    /**
     * 仅供基础设施内部跨租户任务使用，不与任何用户角色绑定。
     */
    public static Scope useInternalBypass() {
        State previous = STATE.get();
        Long tenantId = previous == null ? null : previous.tenantId();
        STATE.set(new State(tenantId, true));
        return new Scope(previous);
    }

    public static boolean isInternalBypass() {
        State state = STATE.get();
        return state != null && state.internalBypass();
    }

    /**
     * 清除当前线程的租户上下文。
     * 在请求结束或登出时必须调用，防止 ThreadLocal 泄漏。
     */
    public static void clear() {
        STATE.remove();
    }

    private static void validateTenantId(Long tenantId) {
        if (tenantId == null || tenantId < 0) {
            throw new IllegalArgumentException("租户 ID 必须为非负整数");
        }
    }

    private record State(Long tenantId, boolean internalBypass) {
    }

    public static final class Scope implements AutoCloseable {
        private final State previous;
        private boolean closed;

        private Scope(State previous) {
            this.previous = previous;
        }

        @Override
        public void close() {
            if (closed) {
                return;
            }
            closed = true;
            if (previous == null) {
                STATE.remove();
            } else {
                STATE.set(previous);
            }
        }
    }
}
