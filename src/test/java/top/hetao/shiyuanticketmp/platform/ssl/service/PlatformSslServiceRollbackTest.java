package top.hetao.shiyuanticketmp.platform.ssl.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import top.hetao.shiyuanticketmp.platform.ssl.certificate.CertificateBundleParser;
import top.hetao.shiyuanticketmp.platform.ssl.crypto.PlatformKeyEncryption;
import top.hetao.shiyuanticketmp.platform.ssl.repository.PlatformSslRepository;
import top.hetao.shiyuanticketmp.platform.ssl.repository.PlatformSslState;
import top.hetao.shiyuanticketmp.platform.ssl.runtime.SslKeyStoreMaterial;
import top.hetao.shiyuanticketmp.platform.ssl.runtime.SslMaterialProvider;
import top.hetao.shiyuanticketmp.platform.ssl.runtime.SslRuntimeController;

import java.util.Optional;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PlatformSslServiceRollbackTest {

    private final PlatformSslRepository repository = mock(PlatformSslRepository.class);
    private final SslMaterialProvider materials = mock(SslMaterialProvider.class);
    private final SslRuntimeController runtime = mock(SslRuntimeController.class);
    private final PlatformSslService service = new PlatformSslService(repository,
            mock(CertificateBundleParser.class), mock(PlatformKeyEncryption.class), materials, runtime,
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
}
