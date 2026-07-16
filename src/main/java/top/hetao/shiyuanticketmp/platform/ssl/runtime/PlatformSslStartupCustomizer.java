package top.hetao.shiyuanticketmp.platform.ssl.runtime;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.web.embedded.tomcat.TomcatServletWebServerFactory;
import org.springframework.boot.web.server.Ssl;
import org.springframework.boot.web.server.WebServerFactoryCustomizer;
import org.springframework.dao.DataAccessException;
import org.springframework.core.Ordered;
import org.springframework.stereotype.Component;
import top.hetao.shiyuanticketmp.platform.ssl.repository.PlatformSslRepository;
import top.hetao.shiyuanticketmp.platform.ssl.repository.PlatformSslState;

import java.nio.file.Path;

/** Resolves the persisted target before Tomcat binds its only connector. */
@Component
public class PlatformSslStartupCustomizer
        implements WebServerFactoryCustomizer<TomcatServletWebServerFactory>, Ordered {

    private static final Logger log = LoggerFactory.getLogger(PlatformSslStartupCustomizer.class);

    private final PlatformSslRepository repository;
    private final SslMaterialProvider materialProvider;
    private final SslKeyStoreFiles keyStoreFiles;
    private volatile Path startupKeyStore;

    public PlatformSslStartupCustomizer(PlatformSslRepository repository,
                                        SslMaterialProvider materialProvider,
                                        SslKeyStoreFiles keyStoreFiles) {
        this.repository = repository;
        this.materialProvider = materialProvider;
        this.keyStoreFiles = keyStoreFiles;
    }

    @Override
    public void customize(TomcatServletWebServerFactory factory) {
        final boolean tablesExist;
        try {
            tablesExist = repository.tablesExist();
        } catch (DataAccessException e) {
            // Database availability is enforced by normal application startup. Do not silently
            // downgrade an existing HTTPS target when the table lookup itself is unavailable.
            throw new IllegalStateException("Cannot resolve platform TLS state before connector binding", e);
        }
        if (!tablesExist) {
            log.info("Platform TLS tables do not exist; binding the server.port connector as HTTP");
            disableFactorySsl(factory);
            return;
        }
        PlatformSslState state = repository.findState().orElseThrow(
                () -> new IllegalStateException("platform_ssl_config singleton row is missing"));
        if (!state.desiredEnabled()) {
            disableFactorySsl(factory);
            return;
        }
        if (state.currentCertificateVersionId() == null) {
            throw new IllegalStateException("Platform TLS is enabled but has no active certificate");
        }
        SslKeyStoreMaterial material = materialProvider.load(state.currentCertificateVersionId());
        startupKeyStore = keyStoreFiles.create(material);
        Ssl ssl = new Ssl();
        ssl.setEnabled(true);
        ssl.setKeyStore(startupKeyStore.toAbsolutePath().toString());
        ssl.setKeyStoreType("PKCS12");
        ssl.setKeyStorePassword("");
        ssl.setKeyAlias("platform");
        factory.setSsl(ssl);
    }

    private void disableFactorySsl(TomcatServletWebServerFactory factory) {
        Ssl ssl = new Ssl();
        ssl.setEnabled(false);
        factory.setSsl(ssl);
    }

    @Override
    public int getOrder() {
        // Override any legacy server.ssl.* properties after Boot's server-properties customizer.
        return Ordered.LOWEST_PRECEDENCE;
    }

    Path startupKeyStore() {
        return startupKeyStore;
    }
}
