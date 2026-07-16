package top.hetao.shiyuanticketmp.tenant.integration;

public class IntegrationVersionConflictException extends RuntimeException {
    public IntegrationVersionConflictException() { super("Integration configuration was changed; reload and retry"); }
}
