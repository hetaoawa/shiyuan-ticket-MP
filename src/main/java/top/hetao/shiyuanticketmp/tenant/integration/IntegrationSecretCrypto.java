package top.hetao.shiyuanticketmp.tenant.integration;

import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;

@Component
public class IntegrationSecretCrypto {
    private final Environment environment;
    private final SecureRandom random = new SecureRandom();

    public IntegrationSecretCrypto(Environment environment) { this.environment = environment; }

    public EncryptedValue encrypt(long tenantId, IntegrationType type, String field, String value) {
        byte[] nonce = new byte[12];
        random.nextBytes(nonce);
        return new EncryptedValue(Base64.getEncoder().encodeToString(crypt(Cipher.ENCRYPT_MODE,
                tenantId, type, field, value.getBytes(StandardCharsets.UTF_8), nonce)),
                Base64.getEncoder().encodeToString(nonce));
    }

    public String decrypt(long tenantId, IntegrationType type, String field, EncryptedValue value) {
        byte[] plaintext = crypt(Cipher.DECRYPT_MODE, tenantId, type, field,
                Base64.getDecoder().decode(value.ciphertext()), Base64.getDecoder().decode(value.nonce()));
        return new String(plaintext, StandardCharsets.UTF_8);
    }

    private byte[] crypt(int mode, long tenantId, IntegrationType type, String field, byte[] input, byte[] nonce) {
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(mode, new SecretKeySpec(rootKey(), "AES"), new GCMParameterSpec(128, nonce));
            cipher.updateAAD(("tenant-integration-v1:" + tenantId + ":" + type + ":" + field)
                    .getBytes(StandardCharsets.UTF_8));
            return cipher.doFinal(input);
        } catch (ConfigurationException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("Unable to protect tenant integration secret", e);
        }
    }

    private byte[] rootKey() {
        String encoded = environment.getProperty("TENANT_INTEGRATION_ROOT_KEY");
        if (encoded == null || encoded.isBlank()) encoded = environment.getProperty("PLATFORM_SSL_ROOT_KEY");
        if (encoded == null || encoded.isBlank())
            throw new ConfigurationException("TENANT_INTEGRATION_ROOT_KEY or PLATFORM_SSL_ROOT_KEY must be configured");
        byte[] key;
        try { key = Base64.getDecoder().decode(encoded.trim()); }
        catch (IllegalArgumentException e) { throw new ConfigurationException("Integration root key is not valid Base64", e); }
        if (key.length != 32) throw new ConfigurationException("Integration root key must decode to 32 bytes");
        return key;
    }

    public static class ConfigurationException extends IllegalStateException {
        public ConfigurationException(String message) {
            super(message);
        }

        public ConfigurationException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    public record EncryptedValue(String ciphertext, String nonce) {}
}
