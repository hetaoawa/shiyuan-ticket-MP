package top.hetao.shiyuanticketmp.platform.ssl.certificate;

import org.bouncycastle.asn1.pkcs.PrivateKeyInfo;
import org.bouncycastle.asn1.sec.ECPrivateKey;
import org.bouncycastle.cert.X509CertificateHolder;
import org.bouncycastle.openssl.PEMKeyPair;
import org.bouncycastle.openssl.PEMParser;
import org.bouncycastle.openssl.jcajce.JcaPEMKeyConverter;
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter;
import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.springframework.stereotype.Component;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.StringReader;
import java.net.IDN;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.security.KeyFactory;
import java.security.MessageDigest;
import java.security.PrivateKey;
import java.security.Signature;
import java.security.cert.Certificate;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import java.security.interfaces.ECPublicKey;
import java.security.spec.ECPrivateKeySpec;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;

@Component
public class CertificateBundleParser {

    private static final char[] PKCS12_PASSWORD = new char[0];

    public CertificateBundle parse(String certificatePem, String privateKeyPem, String expectedDomain) {
        try {
            List<X509Certificate> supplied = parseCertificates(certificatePem);
            PrivateKey privateKey = parsePrivateKey(privateKeyPem, supplied);
            if (!"RSA".equalsIgnoreCase(privateKey.getAlgorithm())
                    && !"EC".equalsIgnoreCase(privateKey.getAlgorithm())) {
                throw new IllegalArgumentException("Only RSA and EC private keys are supported");
            }

            X509Certificate leaf = supplied.stream()
                    .filter(certificate -> keyMatches(certificate, privateKey))
                    .findFirst()
                    .orElseThrow(() -> new IllegalArgumentException("The private key does not match any supplied certificate"));
            List<X509Certificate> ordered = orderChain(leaf, supplied);
            validateChain(ordered);
            List<String> sans = readSans(leaf);
            if (expectedDomain != null && !expectedDomain.isBlank()
                    && sans.stream().noneMatch(san -> domainMatches(expectedDomain, san))) {
                throw new IllegalArgumentException("Certificate SAN does not cover " + expectedDomain);
            }

            KeyStore keyStore = KeyStore.getInstance("PKCS12");
            keyStore.load(null, PKCS12_PASSWORD);
            keyStore.setKeyEntry("platform", privateKey, PKCS12_PASSWORD,
                    ordered.toArray(Certificate[]::new));
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            keyStore.store(output, PKCS12_PASSWORD);
            return new CertificateBundle(
                    output.toByteArray(),
                    HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(leaf.getEncoded())),
                    leaf.getSubjectX500Principal().getName(),
                    List.copyOf(sans),
                    LocalDateTime.ofInstant(leaf.getNotBefore().toInstant(), ZoneId.systemDefault()),
                    LocalDateTime.ofInstant(leaf.getNotAfter().toInstant(), ZoneId.systemDefault()),
                    privateKey.getAlgorithm().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalArgumentException("Invalid TLS certificate or private key: " + e.getMessage(), e);
        }
    }

    public void validatePkcs12(byte[] bytes) {
        try {
            KeyStore keyStore = KeyStore.getInstance("PKCS12");
            keyStore.load(new java.io.ByteArrayInputStream(bytes), PKCS12_PASSWORD);
            if (!keyStore.containsAlias("platform") || !keyStore.isKeyEntry("platform")) {
                throw new IllegalStateException("Encrypted TLS bundle has no platform key entry");
            }
            ((X509Certificate) keyStore.getCertificate("platform")).checkValidity();
        } catch (Exception e) {
            throw new IllegalStateException("Stored TLS bundle is invalid", e);
        }
    }

    private List<X509Certificate> parseCertificates(String pem) throws IOException, CertificateException {
        if (pem == null || pem.isBlank()) {
            throw new IllegalArgumentException("Certificate PEM is required");
        }
        List<X509Certificate> certificates = new ArrayList<>();
        try (PEMParser parser = new PEMParser(new StringReader(pem))) {
            Object object;
            JcaX509CertificateConverter converter = new JcaX509CertificateConverter();
            while ((object = parser.readObject()) != null) {
                if (object instanceof X509CertificateHolder holder) {
                    certificates.add(converter.getCertificate(holder));
                }
            }
        }
        if (certificates.isEmpty()) {
            throw new IllegalArgumentException("No X.509 certificate found in PEM input");
        }
        return certificates;
    }

    private PrivateKey parsePrivateKey(String pem, List<X509Certificate> certificates) throws Exception {
        if (pem == null || pem.isBlank()) {
            throw new IllegalArgumentException("Private key PEM is required");
        }
        try (PEMParser parser = new PEMParser(new StringReader(pem))) {
            Object object = parser.readObject();
            JcaPEMKeyConverter converter = new JcaPEMKeyConverter().setProvider(new BouncyCastleProvider());
            if (object instanceof PEMKeyPair keyPair) {
                if (keyPair.getPublicKeyInfo() == null) {
                    X509Certificate ecCertificate = certificates.stream()
                            .filter(certificate -> certificate.getPublicKey() instanceof ECPublicKey)
                            .findFirst().orElseThrow(() -> new IllegalArgumentException(
                                    "SEC1 EC key requires a matching EC certificate"));
                    ECPrivateKey ecKey = ECPrivateKey.getInstance(
                            keyPair.getPrivateKeyInfo().parsePrivateKey());
                    return KeyFactory.getInstance("EC").generatePrivate(new ECPrivateKeySpec(
                            ecKey.getKey(), ((ECPublicKey) ecCertificate.getPublicKey()).getParams()));
                }
                return converter.getKeyPair(keyPair).getPrivate();
            }
            if (object instanceof PrivateKeyInfo keyInfo) {
                return converter.getPrivateKey(keyInfo);
            }
            throw new IllegalArgumentException("Private key must be unencrypted PKCS#8, PKCS#1, or SEC1 PEM");
        }
    }

    private boolean keyMatches(X509Certificate certificate, PrivateKey privateKey) {
        try {
            String signatureAlgorithm = "EC".equalsIgnoreCase(privateKey.getAlgorithm())
                    ? "SHA256withECDSA" : "SHA256withRSA";
            byte[] challenge = "shiyuan-platform-tls-key-match".getBytes(StandardCharsets.UTF_8);
            Signature signer = Signature.getInstance(signatureAlgorithm);
            signer.initSign(privateKey);
            signer.update(challenge);
            byte[] signature = signer.sign();
            Signature verifier = Signature.getInstance(signatureAlgorithm);
            verifier.initVerify(certificate.getPublicKey());
            verifier.update(challenge);
            return verifier.verify(signature);
        } catch (Exception ignored) {
            return false;
        }
    }

    private List<X509Certificate> orderChain(X509Certificate leaf, List<X509Certificate> supplied) {
        List<X509Certificate> remaining = new ArrayList<>(supplied);
        remaining.remove(leaf);
        List<X509Certificate> ordered = new ArrayList<>();
        ordered.add(leaf);
        X509Certificate current = leaf;
        while (!current.getIssuerX500Principal().equals(current.getSubjectX500Principal())) {
            X509Certificate chainEntry = current;
            X509Certificate issuer = remaining.stream()
                    .filter(candidate -> chainEntry.getIssuerX500Principal().equals(candidate.getSubjectX500Principal()))
                    .findFirst().orElse(null);
            if (issuer == null) {
                break; // Root certificates are optional; Tomcat can serve the supplied intermediates.
            }
            ordered.add(issuer);
            remaining.remove(issuer);
            current = issuer;
        }
        if (!remaining.isEmpty()) {
            throw new IllegalArgumentException("Certificate PEM contains certificates outside the leaf chain");
        }
        return ordered;
    }

    private void validateChain(List<X509Certificate> chain) throws Exception {
        for (int i = 0; i < chain.size(); i++) {
            chain.get(i).checkValidity();
            if (i + 1 < chain.size()) {
                chain.get(i).verify(chain.get(i + 1).getPublicKey());
            }
        }
    }

    private List<String> readSans(X509Certificate certificate) throws Exception {
        Collection<List<?>> values = certificate.getSubjectAlternativeNames();
        List<String> sans = new ArrayList<>();
        if (values == null) {
            return sans;
        }
        for (List<?> value : values) {
            int type = ((Number) value.get(0)).intValue();
            if ((type == 2 || type == 7) && value.size() > 1) {
                sans.add(String.valueOf(value.get(1)));
            }
        }
        return sans;
    }

    static boolean domainMatches(String requestedDomain, String certificateName) {
        String domain = IDN.toASCII(requestedDomain.trim()).toLowerCase(Locale.ROOT);
        String san = IDN.toASCII(certificateName.trim()).toLowerCase(Locale.ROOT);
        if (san.startsWith("*.")) {
            String suffix = san.substring(1);
            return domain.endsWith(suffix)
                    && domain.substring(0, domain.length() - suffix.length()).indexOf('.') < 0;
        }
        return domain.equals(san);
    }
}
