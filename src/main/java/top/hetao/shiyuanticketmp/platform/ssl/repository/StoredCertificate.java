package top.hetao.shiyuanticketmp.platform.ssl.repository;

import java.time.LocalDateTime;

public record StoredCertificate(
        long id,
        byte[] encryptedPkcs12,
        byte[] nonce,
        String fingerprintSha256,
        String subjectDn,
        String sansJson,
        LocalDateTime notBefore,
        LocalDateTime notAfter,
        String keyAlgorithm,
        String source,
        String status,
        LocalDateTime createdAt) {
}
