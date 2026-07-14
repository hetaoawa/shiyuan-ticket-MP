package top.hetao.shiyuanticketmp.platform.ssl.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import top.hetao.shiyuanticketmp.platform.ssl.certificate.CertificateBundle;
import top.hetao.shiyuanticketmp.platform.ssl.certificate.CertificateBundleParser;
import top.hetao.shiyuanticketmp.platform.ssl.crypto.EncryptedPayload;
import top.hetao.shiyuanticketmp.platform.ssl.crypto.PlatformKeyEncryption;
import top.hetao.shiyuanticketmp.platform.ssl.repository.PlatformSslRepository;
import top.hetao.shiyuanticketmp.platform.ssl.repository.PlatformSslState;
import top.hetao.shiyuanticketmp.platform.ssl.runtime.SslKeyStoreMaterial;
import top.hetao.shiyuanticketmp.platform.ssl.runtime.SslMaterialProvider;
import top.hetao.shiyuanticketmp.platform.ssl.runtime.SslRuntimeController;
import top.hetao.shiyuanticketmp.platform.ssl.runtime.SslRuntimeTransition;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PlatformSslServiceRollbackTest {

    private final PlatformSslRepository repository = mock(PlatformSslRepository.class);
    private final CertificateBundleParser parser = mock(CertificateBundleParser.class);
    private final PlatformKeyEncryption encryption = mock(PlatformKeyEncryption.class);
    private final SslMaterialProvider materials = mock(SslMaterialProvider.class);
    private final SslRuntimeController runtime = mock(SslRuntimeController.class);
    private final PlatformSslService service = new PlatformSslService(repository,
            parser, encryption, materials, runtime,
            new ObjectMapper());

    @AfterEach
    void stopExecutor() {
        service.shutdown();
    }

    @Test
    void restoresPersistedStateWhenRuntimeEnableFails() throws Exception {
        PlatformSslState previous = new PlatformSslState(false, false, 42L,
                "service.example.com", "DNS-01", false, null);
        when(repository.findState()).thenReturn(Optional.of(previous));
        when(repository.insertOperation("ENABLE", false, true, 42L, 7L)).thenReturn(99L);
        when(materials.load(42L)).thenReturn(new SslKeyStoreMaterial(42L, new byte[]{1}));
        org.mockito.Mockito.doThrow(new IllegalStateException("connector failed"))
                .when(runtime).enable(org.mockito.ArgumentMatchers.any());

        service.requestStateChange(true, 7L);

        verify(repository, timeout(2000)).completeOperation(99L, false, "connector failed");
        verify(repository, timeout(2000)).updateState(
                eq(false), eq(false), eq(42L), eq("service.example.com"), eq("connector failed"), eq(7L));
    }

    @Test
    void rollsBackEnabledConnectorBeforeRestoringStateWhenFinalStateWriteFails() throws Exception {
        PlatformSslState previous = new PlatformSslState(false, false, 42L,
                "service.example.com", "DNS-01", false, null);
        SslRuntimeTransition transition = mock(SslRuntimeTransition.class);
        when(repository.findState()).thenReturn(Optional.of(previous));
        when(repository.insertOperation("ENABLE", false, true, 42L, 7L)).thenReturn(100L);
        when(materials.load(42L)).thenReturn(new SslKeyStoreMaterial(42L, new byte[]{1}));
        when(runtime.enable(any())).thenReturn(transition);
        doThrow(new IllegalStateException("final state failed")).when(repository).updateState(
                true, true, 42L, "service.example.com", null, 7L);

        service.requestStateChange(true, 7L);

        verify(repository, timeout(2000)).completeOperation(100L, false, "final state failed");
        InOrder order = inOrder(transition, repository);
        order.verify(transition).rollback();
        order.verify(repository).updateState(
                false, false, 42L, "service.example.com", "final state failed", 7L);
        verify(transition, never()).commit();
    }

    @Test
    void rollsBackReloadAndReactivatesOldCertificateWhenFinalStateWriteFails() throws Exception {
        PlatformSslState previous = new PlatformSslState(true, true, 42L,
                "service.example.com", "DNS-01", false, null);
        SslRuntimeTransition transition = mock(SslRuntimeTransition.class);
        CertificateBundle bundle = new CertificateBundle(new byte[]{9}, "fingerprint", "subject",
                List.of("service.example.com"), LocalDateTime.now().minusDays(1),
                LocalDateTime.now().plusDays(30), "RSA");
        EncryptedPayload encrypted = new EncryptedPayload(new byte[]{2}, new byte[]{3});
        when(repository.findState()).thenReturn(Optional.of(previous));
        when(parser.parse(anyString(), anyString(), eq("service.example.com"))).thenReturn(bundle);
        when(encryption.encrypt(bundle.pkcs12())).thenReturn(encrypted);
        when(repository.insertCertificate(bundle, encrypted, "ADMIN_UPLOAD", 7L)).thenReturn(77L);
        when(repository.insertOperation("IMPORT_CERTIFICATE", true, true, 77L, 7L)).thenReturn(101L);
        when(materials.load(77L)).thenReturn(new SslKeyStoreMaterial(77L, new byte[]{9}));
        when(runtime.reload(any())).thenReturn(transition);
        doThrow(new IllegalStateException("certificate state failed")).when(repository).updateState(
                true, true, 77L, "service.example.com", null, 7L);

        service.importCertificate("certificate".getBytes(), "key".getBytes(),
                "service.example.com", "ADMIN_UPLOAD", 7L, false);

        verify(repository, timeout(2000)).completeOperation(101L, false, "certificate state failed");
        InOrder order = inOrder(transition, repository);
        order.verify(transition).rollback();
        order.verify(repository).restoreCertificate(42L, 77L);
        order.verify(repository).updateState(
                true, true, 42L, "service.example.com", "certificate state failed", 7L);
        verify(transition, never()).commit();
    }
}
