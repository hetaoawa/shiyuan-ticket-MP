package top.hetao.shiyuanticketmp.platform.ssl.service;

import java.time.LocalDateTime;
import java.util.List;

public record PlatformSslStatus(
        boolean desiredEnabled,
        boolean effectiveEnabled,
        String domainName,
        String challengeType,
        boolean legacyImportCompleted,
        boolean rootKeyConfigured,
        String lastError,
        CertificateSummary certificate) {

    public record CertificateSummary(long id, String fingerprintSha256, String subjectDn,
                                     List<String> subjectAlternativeNames, LocalDateTime notBefore,
                                     LocalDateTime notAfter, String keyAlgorithm, String source,
                                     String status, LocalDateTime createdAt) {
    }
}
