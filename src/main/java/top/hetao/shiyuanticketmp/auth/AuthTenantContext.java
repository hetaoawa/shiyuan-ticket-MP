package top.hetao.shiyuanticketmp.auth;

/** Immutable authenticated tenant identity stored in the Sa-Token session. */
public record AuthTenantContext(
        Long principalTenantId,
        Long activeTenantId,
        String activeTenantCode,
        String activeTenantName,
        boolean globalAdmin) {
}
