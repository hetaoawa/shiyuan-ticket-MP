package top.hetao.shiyuanticketmp.platform.ssl.certificate;

import java.time.LocalDateTime;
import java.util.List;

public record CertificateBundle(
        byte[] pkcs12,
        String fingerprintSha256,
        String subjectDn,
        List<String> subjectAlternativeNames,
        LocalDateTime notBefore,
        LocalDateTime notAfter,
        String keyAlgorithm) {
}
