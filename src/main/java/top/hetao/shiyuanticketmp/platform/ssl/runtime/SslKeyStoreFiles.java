package top.hetao.shiyuanticketmp.platform.ssl.runtime;

import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;

@Component
public class SslKeyStoreFiles {

    public Path create(SslKeyStoreMaterial material) {
        try {
            Path path = Files.createTempFile("shiyuan-tls-" + material.certificateVersionId() + "-", ".p12");
            Files.write(path, material.pkcs12());
            try {
                Files.setPosixFilePermissions(path, PosixFilePermissions.fromString("rw-------"));
            } catch (UnsupportedOperationException ignored) {
                // Windows ACLs inherit from the process temp directory.
            }
            path.toFile().deleteOnExit();
            return path;
        } catch (IOException e) {
            throw new IllegalStateException("Unable to materialize the platform TLS key store", e);
        }
    }

    public void delete(Path path) {
        if (path == null) return;
        try {
            Files.deleteIfExists(path);
        } catch (IOException ignored) {
            path.toFile().deleteOnExit();
        }
    }
}
