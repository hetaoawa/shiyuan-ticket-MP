package top.hetao.shiyuanticketmp.tenant.integration;

public enum IntegrationType {
    DINGTALK, CARGO_OWNER, S3, EXPRESS, AI;

    public static IntegrationType parse(String value) {
        try {
            return valueOf(value == null ? "" : value.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Unsupported integration type: " + value);
        }
    }
}
