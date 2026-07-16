package top.hetao.shiyuanticketmp.platform.ssl.runtime;

import org.apache.catalina.connector.Connector;
import org.apache.coyote.http11.AbstractHttp11Protocol;
import org.apache.tomcat.util.net.SSLHostConfig;
import org.apache.tomcat.util.net.SSLHostConfigCertificate;
import org.springframework.boot.web.embedded.tomcat.TomcatWebServer;
import org.springframework.boot.web.context.WebServerInitializedEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.dao.DataAccessException;
import top.hetao.shiyuanticketmp.platform.ssl.repository.PlatformSslRepository;

import java.nio.file.Path;

/** Mutates the single server.port connector. Connector stop/start is intentional and bounded. */
@Component
public class TomcatSslRuntimeController implements SslRuntimeController {

    private static final String DEFAULT_HOST = "_default_";

    private final SslKeyStoreFiles keyStoreFiles;
    private final PlatformSslStartupCustomizer startupCustomizer;
    private final PlatformSslRepository repository;
    private volatile Connector connector;
    private volatile Path activeKeyStore;

    public TomcatSslRuntimeController(SslKeyStoreFiles keyStoreFiles,
                                      PlatformSslStartupCustomizer startupCustomizer,
                                      PlatformSslRepository repository) {
        this.keyStoreFiles = keyStoreFiles;
        this.startupCustomizer = startupCustomizer;
        this.repository = repository;
    }

    @EventListener
    public void webServerReady(WebServerInitializedEvent event) {
        if (event.getWebServer() instanceof TomcatWebServer tomcatWebServer) {
            this.connector = tomcatWebServer.getTomcat().getConnector();
            this.activeKeyStore = startupCustomizer.startupKeyStore();
            try {
                repository.findState().ifPresent(state -> {
                    boolean actualHttps = protocol(this.connector).isSSLEnabled();
                    if (state.effectiveEnabled() != actualHttps) {
                        repository.updateState(state.desiredEnabled(), actualHttps,
                                state.currentCertificateVersionId(), state.domainName(), state.lastError(), null);
                    }
                });
            } catch (DataAccessException ignored) {
                // A normal datasource/Flyway failure will already fail application startup.
            }
        }
    }

    @Override
    public boolean isHttps() {
        Connector current = requireConnector();
        return protocol(current).isSSLEnabled();
    }

    @Override
    public synchronized SslRuntimeTransition enable(SslKeyStoreMaterial material) throws Exception {
        Path target = keyStoreFiles.create(material);
        Path previous = activeKeyStore;
        boolean previousHttps = isHttps();
        try {
            reconfigure(true, target);
            activeKeyStore = target;
            return transition(previous, target, () -> {
                reconfigure(previousHttps, previous);
                activeKeyStore = previous;
            });
        } catch (Exception failure) {
            rollback(previousHttps, previous, failure);
            keyStoreFiles.delete(target);
            throw failure;
        }
    }

    @Override
    public synchronized SslRuntimeTransition disable() throws Exception {
        Path previous = activeKeyStore;
        boolean previousHttps = isHttps();
        try {
            reconfigure(false, null);
            activeKeyStore = null;
            return transition(previous, null, () -> {
                reconfigure(previousHttps, previous);
                activeKeyStore = previous;
            });
        } catch (Exception failure) {
            rollback(previousHttps, previous, failure);
            throw failure;
        }
    }

    @Override
    public synchronized SslRuntimeTransition reload(SslKeyStoreMaterial material) throws Exception {
        if (!isHttps()) {
            throw new IllegalStateException("Cannot hot-reload TLS while the connector is HTTP");
        }
        Path target = keyStoreFiles.create(material);
        Path previous = activeKeyStore;
        try {
            configureHost(protocol(requireConnector()), target);
            protocol(requireConnector()).reloadSslHostConfig(DEFAULT_HOST);
            activeKeyStore = target;
            return transition(previous, target, () -> {
                if (previous == null) {
                    throw new IllegalStateException("Previous TLS key store is missing");
                }
                configureHost(protocol(requireConnector()), previous);
                protocol(requireConnector()).reloadSslHostConfig(DEFAULT_HOST);
                activeKeyStore = previous;
            });
        } catch (Exception failure) {
            keyStoreFiles.delete(target);
            if (previous != null) {
                try {
                    configureHost(protocol(requireConnector()), previous);
                    protocol(requireConnector()).reloadSslHostConfig(DEFAULT_HOST);
                } catch (Exception rollbackFailure) {
                    failure.addSuppressed(rollbackFailure);
                }
            }
            throw failure;
        }
    }

    private void rollback(boolean previousHttps, Path previous, Exception failure) {
        try {
            reconfigure(previousHttps, previous);
            activeKeyStore = previous;
        } catch (Exception rollbackFailure) {
            failure.addSuppressed(rollbackFailure);
        }
    }

    private SslRuntimeTransition transition(Path previous, Path target, RollbackAction rollbackAction) {
        return new SslRuntimeTransition() {
            private boolean finished;

            @Override
            public void commit() {
                synchronized (TomcatSslRuntimeController.this) {
                    if (finished) return;
                    finished = true;
                    if (previous != null && !previous.equals(target)) keyStoreFiles.delete(previous);
                }
            }

            @Override
            public void rollback() throws Exception {
                synchronized (TomcatSslRuntimeController.this) {
                    if (finished) return;
                    rollbackAction.run();
                    finished = true;
                    if (target != null && !target.equals(previous)) keyStoreFiles.delete(target);
                }
            }
        };
    }

    @FunctionalInterface
    private interface RollbackAction {
        void run() throws Exception;
    }

    private void reconfigure(boolean https, Path keyStore) throws Exception {
        Connector current = requireConnector();
        AbstractHttp11Protocol<?> protocol = protocol(current);
        current.stop();
        try {
            if (https) {
                if (keyStore == null) throw new IllegalStateException("TLS key store is missing");
                configureHost(protocol, keyStore);
                protocol.setSSLEnabled(true);
                current.setScheme("https");
                current.setSecure(true);
            } else {
                protocol.setSSLEnabled(false);
                current.setScheme("http");
                current.setSecure(false);
            }
        } finally {
            current.start();
        }
    }

    private void configureHost(AbstractHttp11Protocol<?> protocol, Path keyStore) {
        SSLHostConfig hostConfig = java.util.Arrays.stream(protocol.findSslHostConfigs())
                .filter(config -> DEFAULT_HOST.equals(config.getHostName()))
                .findFirst().orElse(null);
        boolean newHost = hostConfig == null;
        if (newHost) {
            hostConfig = new SSLHostConfig();
            hostConfig.setHostName(DEFAULT_HOST);
        }
        SSLHostConfigCertificate certificate = hostConfig.getCertificates().stream().findFirst().orElse(null);
        if (certificate == null) {
            certificate = new SSLHostConfigCertificate(hostConfig, SSLHostConfigCertificate.Type.UNDEFINED);
            hostConfig.addCertificate(certificate);
        }
        certificate.setCertificateKeystoreFile(keyStore.toAbsolutePath().toString());
        certificate.setCertificateKeystoreType("PKCS12");
        certificate.setCertificateKeystorePassword("");
        certificate.setCertificateKeyPassword("");
        certificate.setCertificateKeyAlias("platform");
        if (newHost) protocol.addSslHostConfig(hostConfig);
    }

    private Connector requireConnector() {
        Connector current = connector;
        if (current == null) throw new IllegalStateException("Embedded Tomcat connector is not initialized");
        return current;
    }

    private AbstractHttp11Protocol<?> protocol(Connector current) {
        if (!(current.getProtocolHandler() instanceof AbstractHttp11Protocol<?> http11)) {
            throw new IllegalStateException("Platform TLS requires Tomcat HTTP/1.1 protocol handler");
        }
        return http11;
    }
}
