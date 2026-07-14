package top.hetao.shiyuanticketmp.platform.ssl.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PreDestroy;
import org.springframework.stereotype.Service;
import top.hetao.shiyuanticketmp.platform.ssl.certificate.CertificateBundle;
import top.hetao.shiyuanticketmp.platform.ssl.certificate.CertificateBundleParser;
import top.hetao.shiyuanticketmp.platform.ssl.crypto.EncryptedPayload;
import top.hetao.shiyuanticketmp.platform.ssl.crypto.PlatformKeyEncryption;
import top.hetao.shiyuanticketmp.platform.ssl.repository.PlatformSslOperationView;
import top.hetao.shiyuanticketmp.platform.ssl.repository.PlatformSslRepository;
import top.hetao.shiyuanticketmp.platform.ssl.repository.PlatformSslState;
import top.hetao.shiyuanticketmp.platform.ssl.repository.StoredCertificate;
import top.hetao.shiyuanticketmp.platform.ssl.runtime.SslMaterialProvider;
import top.hetao.shiyuanticketmp.platform.ssl.runtime.SslRuntimeController;
import top.hetao.shiyuanticketmp.platform.ssl.runtime.SslRuntimeTransition;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@Service
public class PlatformSslService {

    private static final int MAX_PEM_BYTES = 1024 * 1024;

    private final PlatformSslRepository repository;
    private final CertificateBundleParser parser;
    private final PlatformKeyEncryption encryption;
    private final SslMaterialProvider materialProvider;
    private final SslRuntimeController runtimeController;
    private final ObjectMapper objectMapper;
    private final SecureRandom secureRandom = new SecureRandom();
    private final ExecutorService transitions = Executors.newSingleThreadExecutor(r -> {
        Thread thread = new Thread(r, "platform-ssl-transition");
        thread.setDaemon(true);
        return thread;
    });

    public PlatformSslService(PlatformSslRepository repository, CertificateBundleParser parser,
                              PlatformKeyEncryption encryption, SslMaterialProvider materialProvider,
                              SslRuntimeController runtimeController, ObjectMapper objectMapper) {
        this.repository = repository;
        this.parser = parser;
        this.encryption = encryption;
        this.materialProvider = materialProvider;
        this.runtimeController = runtimeController;
        this.objectMapper = objectMapper;
    }

    public PlatformSslStatus status() {
        PlatformSslState state = requireState();
        PlatformSslStatus.CertificateSummary certificate = null;
        if (state.currentCertificateVersionId() != null) {
            StoredCertificate stored = repository.requireCertificate(state.currentCertificateVersionId());
            certificate = new PlatformSslStatus.CertificateSummary(
                    stored.id(), stored.fingerprintSha256(), stored.subjectDn(), parseSans(stored.sansJson()),
                    stored.notBefore(), stored.notAfter(), stored.keyAlgorithm(), stored.source(),
                    stored.status(), stored.createdAt());
        }
        return new PlatformSslStatus(state.desiredEnabled(), state.effectiveEnabled(), state.domainName(),
                state.challengeType(), state.legacyImportCompleted(), encryption.isRootKeyConfigured(),
                state.lastError(), certificate);
    }

    public long requestStateChange(boolean enabled, long actorId) {
        PlatformSslState state = requireState();
        long operationId = repository.insertOperation(enabled ? "ENABLE" : "DISABLE",
                state.effectiveEnabled(), enabled, state.currentCertificateVersionId(), actorId);
        transitions.submit(() -> executeStateChange(operationId, enabled, actorId));
        return operationId;
    }

    public synchronized CertificateImportResult importCertificate(byte[] certificatePem, byte[] privateKeyPem,
                                                                  String domain, String source, Long actorId,
                                                                  boolean legacyImport) {
        validatePemSize(certificatePem, privateKeyPem);
        PlatformSslState state = requireState();
        if (legacyImport && (state.legacyImportCompleted() || state.currentCertificateVersionId() != null)) {
            throw new PlatformSslException("Legacy TLS import is a one-time bootstrap and is already closed");
        }
        String targetDomain = domain == null || domain.isBlank() ? state.domainName() : domain.trim();
        final CertificateBundle bundle;
        try {
            bundle = parser.parse(new String(certificatePem, StandardCharsets.UTF_8),
                    new String(privateKeyPem, StandardCharsets.UTF_8), targetDomain);
        } catch (IllegalArgumentException e) {
            throw new PlatformSslException(e.getMessage(), e);
        }
        EncryptedPayload encrypted = encryption.encrypt(bundle.pkcs12());
        long certificateId = repository.insertCertificate(bundle, encrypted, source, actorId);
        long operationId = repository.insertOperation(legacyImport ? "LEGACY_IMPORT" : "IMPORT_CERTIFICATE",
                state.effectiveEnabled(), state.effectiveEnabled(), certificateId, actorId);
        transitions.submit(() -> executeCertificateInstall(operationId, certificateId, targetDomain,
                actorId, legacyImport));
        return new CertificateImportResult(certificateId, operationId);
    }

    public DeployTokenResult createDeployToken(String name, int expiresInMinutes, long actorId) {
        if (expiresInMinutes < 1 || expiresInMinutes > 1440) {
            throw new PlatformSslException("Deploy token lifetime must be between 1 and 1440 minutes");
        }
        byte[] random = new byte[32];
        secureRandom.nextBytes(random);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(random);
        LocalDateTime expiresAt = LocalDateTime.now().plusMinutes(expiresInMinutes);
        long id = repository.insertDeployToken(
                name == null || name.isBlank() ? "acme.sh" : name.trim(), sha256(token), expiresAt, actorId);
        return new DeployTokenResult(id, token, expiresAt);
    }

    public CertificateImportResult importFromDeployToken(String token, byte[] certificatePem,
                                                         byte[] privateKeyPem, String domain) {
        if (token == null || token.isBlank() || !repository.consumeDeployToken(sha256(token.trim()))) {
            throw new InvalidDeployTokenException();
        }
        return importCertificate(certificatePem, privateKeyPem, domain, "ACME_SH", null, false);
    }

    public List<PlatformSslOperationView> recentOperations(int limit) {
        return repository.recentOperations(Math.max(1, Math.min(limit, 100)));
    }

    private void executeStateChange(long operationId, boolean enabled, long actorId) {
        PlatformSslState previous = requireState();
        SslRuntimeTransition runtimeTransition = null;
        try {
            repository.updateState(enabled, previous.effectiveEnabled(), previous.currentCertificateVersionId(),
                    previous.domainName(), null, actorId);
            if (enabled) {
                if (previous.currentCertificateVersionId() == null) {
                    throw new IllegalStateException("Upload a valid certificate before enabling HTTPS");
                }
                runtimeTransition = runtimeController.enable(
                        materialProvider.load(previous.currentCertificateVersionId()));
            } else {
                runtimeTransition = runtimeController.disable();
            }
            repository.updateState(enabled, enabled, previous.currentCertificateVersionId(),
                    previous.domainName(), null, actorId);
            repository.completeOperation(operationId, true, enabled ? "HTTPS enabled" : "HTTP enabled");
            runtimeTransition.commit();
        } catch (Exception e) {
            rollbackRuntime(runtimeTransition, e);
            restoreState(operationId, previous, actorId, e);
        }
    }

    private void executeCertificateInstall(long operationId, long certificateId, String domain,
                                           Long actorId, boolean legacyImport) {
        PlatformSslState previous = requireState();
        SslRuntimeTransition runtimeTransition = null;
        try {
            if (previous.effectiveEnabled()) {
                runtimeTransition = runtimeController.reload(materialProvider.load(certificateId));
            }
            repository.activateCertificate(certificateId);
            repository.updateState(previous.desiredEnabled(), previous.effectiveEnabled(), certificateId,
                    domain, null, actorId);
            if (legacyImport) repository.markLegacyImported();
            repository.completeOperation(operationId, true,
                    previous.effectiveEnabled() ? "Certificate hot-reloaded" : "Certificate staged");
            if (runtimeTransition != null) runtimeTransition.commit();
        } catch (Exception e) {
            rollbackRuntime(runtimeTransition, e);
            restoreCertificateState(certificateId, previous, legacyImport, e);
            restoreState(operationId, previous, actorId, e);
        }
    }

    private void rollbackRuntime(SslRuntimeTransition transition, Exception failure) {
        if (transition == null) return;
        try {
            transition.rollback();
        } catch (Exception rollbackFailure) {
            failure.addSuppressed(rollbackFailure);
        }
    }

    private void restoreCertificateState(long certificateId, PlatformSslState previous,
                                         boolean legacyImport, Exception failure) {
        try {
            repository.restoreCertificate(previous.currentCertificateVersionId(), certificateId);
        } catch (Exception restoreFailure) {
            failure.addSuppressed(restoreFailure);
        }
        if (legacyImport) {
            try {
                repository.updateLegacyImported(previous.legacyImportCompleted());
            } catch (Exception restoreFailure) {
                failure.addSuppressed(restoreFailure);
            }
        }
    }

    private void restoreState(long operationId, PlatformSslState previous, Long actorId, Exception failure) {
        String message = rootMessage(failure);
        try {
            repository.updateState(previous.desiredEnabled(), previous.effectiveEnabled(),
                    previous.currentCertificateVersionId(), previous.domainName(), message, actorId);
        } catch (Exception restoreFailure) {
            failure.addSuppressed(restoreFailure);
        }
        try {
            repository.completeOperation(operationId, false, message);
        } catch (Exception restoreFailure) {
            failure.addSuppressed(restoreFailure);
        }
    }

    private PlatformSslState requireState() {
        return repository.findState().orElseThrow(
                () -> new PlatformSslException("Platform TLS tables are not initialized; apply V26 first"));
    }

    private void validatePemSize(byte[] certificatePem, byte[] privateKeyPem) {
        if (certificatePem == null || privateKeyPem == null
                || certificatePem.length == 0 || privateKeyPem.length == 0) {
            throw new PlatformSslException("Certificate and private key PEM files are required");
        }
        if (certificatePem.length > MAX_PEM_BYTES || privateKeyPem.length > MAX_PEM_BYTES) {
            throw new PlatformSslException("Each PEM file must be at most 1 MiB");
        }
    }

    private byte[] sha256(String value) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private List<String> parseSans(String json) {
        try {
            return objectMapper.readValue(json, new TypeReference<>() { });
        } catch (Exception e) {
            throw new IllegalStateException("Stored certificate SAN metadata is invalid", e);
        }
    }

    private String rootMessage(Throwable throwable) {
        Throwable root = throwable;
        while (root.getCause() != null) root = root.getCause();
        return root.getMessage() == null ? root.getClass().getSimpleName() : root.getMessage();
    }

    @PreDestroy
    public void shutdown() {
        transitions.shutdown();
    }
}
