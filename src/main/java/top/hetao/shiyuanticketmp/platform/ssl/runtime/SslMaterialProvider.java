package top.hetao.shiyuanticketmp.platform.ssl.runtime;

import org.springframework.stereotype.Component;
import top.hetao.shiyuanticketmp.platform.ssl.certificate.CertificateBundleParser;
import top.hetao.shiyuanticketmp.platform.ssl.crypto.PlatformKeyEncryption;
import top.hetao.shiyuanticketmp.platform.ssl.repository.PlatformSslRepository;
import top.hetao.shiyuanticketmp.platform.ssl.repository.StoredCertificate;

@Component
public class SslMaterialProvider {

    private final PlatformSslRepository repository;
    private final PlatformKeyEncryption encryption;
    private final CertificateBundleParser parser;

    public SslMaterialProvider(PlatformSslRepository repository, PlatformKeyEncryption encryption,
                               CertificateBundleParser parser) {
        this.repository = repository;
        this.encryption = encryption;
        this.parser = parser;
    }

    public SslKeyStoreMaterial load(long certificateVersionId) {
        StoredCertificate certificate = repository.requireCertificate(certificateVersionId);
        byte[] pkcs12 = encryption.decrypt(certificate.encryptedPkcs12(), certificate.nonce());
        parser.validatePkcs12(pkcs12);
        return new SslKeyStoreMaterial(certificate.id(), pkcs12);
    }
}
