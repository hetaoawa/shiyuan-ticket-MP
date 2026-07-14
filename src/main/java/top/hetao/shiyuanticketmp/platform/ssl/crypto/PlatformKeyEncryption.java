package top.hetao.shiyuanticketmp.platform.ssl.crypto;

import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Base64;

@Component
public class PlatformKeyEncryption {

    private static final int NONCE_LENGTH = 12;
    private static final int TAG_BITS = 128;
    private static final byte[] AAD = "shiyuan-platform-ssl-pkcs12-v1".getBytes(java.nio.charset.StandardCharsets.UTF_8);

    private final Environment environment;
    private final SecureRandom secureRandom;

    public PlatformKeyEncryption(Environment environment) {
        this(environment, new SecureRandom());
    }

    PlatformKeyEncryption(Environment environment, SecureRandom secureRandom) {
        this.environment = environment;
        this.secureRandom = secureRandom;
    }

    public EncryptedPayload encrypt(byte[] plaintext) {
        byte[] nonce = new byte[NONCE_LENGTH];
        secureRandom.nextBytes(nonce);
        return new EncryptedPayload(crypt(Cipher.ENCRYPT_MODE, plaintext, nonce), nonce);
    }

    public byte[] decrypt(byte[] ciphertext, byte[] nonce) {
        if (nonce == null || nonce.length != NONCE_LENGTH) {
            throw new IllegalStateException("Invalid platform SSL encryption nonce");
        }
        return crypt(Cipher.DECRYPT_MODE, ciphertext, nonce);
    }

    public boolean isRootKeyConfigured() {
        String value = environment.getProperty("PLATFORM_SSL_ROOT_KEY");
        return value != null && !value.isBlank();
    }

    private byte[] crypt(int mode, byte[] input, byte[] nonce) {
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(mode, new SecretKeySpec(rootKey(), "AES"), new GCMParameterSpec(TAG_BITS, nonce));
            cipher.updateAAD(AAD);
            return cipher.doFinal(input);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Unable to encrypt/decrypt the platform TLS key material", e);
        }
    }

    private byte[] rootKey() {
        String encoded = environment.getProperty("PLATFORM_SSL_ROOT_KEY");
        if (encoded == null || encoded.isBlank()) {
            throw new IllegalStateException("PLATFORM_SSL_ROOT_KEY must be set to a Base64-encoded 256-bit key");
        }
        final byte[] key;
        try {
            key = Base64.getDecoder().decode(encoded.trim());
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException("PLATFORM_SSL_ROOT_KEY is not valid Base64", e);
        }
        if (key.length != 32) {
            throw new IllegalStateException("PLATFORM_SSL_ROOT_KEY must decode to exactly 32 bytes");
        }
        return key;
    }
}
