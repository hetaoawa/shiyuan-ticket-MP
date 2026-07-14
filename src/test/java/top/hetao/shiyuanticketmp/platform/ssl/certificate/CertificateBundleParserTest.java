package top.hetao.shiyuanticketmp.platform.ssl.certificate;

import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.asn1.x509.Extension;
import org.bouncycastle.asn1.x509.GeneralName;
import org.bouncycastle.asn1.x509.GeneralNames;
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter;
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder;
import org.bouncycastle.openssl.jcajce.JcaPEMWriter;
import org.bouncycastle.operator.ContentSigner;
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder;
import org.junit.jupiter.api.Test;

import java.io.StringWriter;
import java.math.BigInteger;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.spec.ECGenParameterSpec;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Date;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CertificateBundleParserTest {

    private final CertificateBundleParser parser = new CertificateBundleParser();

    @Test
    void acceptsRsaCertificateAndValidatesSanAndKey() throws Exception {
        PemPair pair = create("RSA", "service.example.com");

        CertificateBundle bundle = parser.parse(pair.certificate(), pair.privateKey(), "service.example.com");

        assertThat(bundle.keyAlgorithm()).isEqualTo("RSA");
        assertThat(bundle.subjectAlternativeNames()).containsExactly("service.example.com");
        assertThat(bundle.pkcs12()).isNotEmpty();
        parser.validatePkcs12(bundle.pkcs12());
    }

    @Test
    void acceptsEcCertificateAndWildcardForOneLabelOnly() throws Exception {
        PemPair pair = create("EC", "*.example.com");

        CertificateBundle bundle = parser.parse(pair.certificate(), pair.privateKey(), "api.example.com");

        assertThat(bundle.keyAlgorithm()).isEqualTo("EC");
        assertThatThrownBy(() -> parser.parse(pair.certificate(), pair.privateKey(), "deep.api.example.com"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("SAN");
    }

    @Test
    void rejectsMismatchedPrivateKey() throws Exception {
        PemPair certificate = create("RSA", "service.example.com");
        PemPair other = create("RSA", "service.example.com");

        assertThatThrownBy(() -> parser.parse(certificate.certificate(), other.privateKey(), null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("does not match");
    }

    private PemPair create(String algorithm, String san) throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance(algorithm);
        if ("RSA".equals(algorithm)) generator.initialize(2048);
        else generator.initialize(new ECGenParameterSpec("secp256r1"));
        KeyPair keyPair = generator.generateKeyPair();
        X500Name name = new X500Name("CN=" + san);
        Instant now = Instant.now();
        JcaX509v3CertificateBuilder builder = new JcaX509v3CertificateBuilder(
                name, BigInteger.valueOf(Math.abs(System.nanoTime())),
                Date.from(now.minus(1, ChronoUnit.DAYS)), Date.from(now.plus(30, ChronoUnit.DAYS)),
                name, keyPair.getPublic());
        builder.addExtension(Extension.subjectAlternativeName, false,
                new GeneralNames(new GeneralName(GeneralName.dNSName, san)));
        ContentSigner signer = new JcaContentSignerBuilder(
                "RSA".equals(algorithm) ? "SHA256withRSA" : "SHA256withECDSA")
                .build(keyPair.getPrivate());
        var certificate = new JcaX509CertificateConverter().getCertificate(builder.build(signer));
        return new PemPair(pem(certificate), pem(keyPair.getPrivate()));
    }

    private String pem(Object value) throws Exception {
        StringWriter output = new StringWriter();
        try (JcaPEMWriter writer = new JcaPEMWriter(output)) {
            writer.writeObject(value);
        }
        return output.toString();
    }

    private record PemPair(String certificate, String privateKey) { }
}
