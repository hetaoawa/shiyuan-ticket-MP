package top.hetao.shiyuanticketmp.platform.ssl.repository;

public record PlatformSslState(
        boolean desiredEnabled,
        boolean effectiveEnabled,
        Long currentCertificateVersionId,
        String domainName,
        String challengeType,
        boolean legacyImportCompleted,
        String lastError) {
}
