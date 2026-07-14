package top.hetao.shiyuanticketmp.platform.ssl.crypto;

import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.mock.env.MockEnvironment;

import javax.crypto.AEADBadTagException;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PlatformKeyEncryptionTest {

    @Test
    void springSelectsTheEnvironmentConstructorWhenTestConstructorAlsoExists() {
        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()) {
            context.register(PlatformKeyEncryption.class);
            context.refresh();

            assertThat(context.getBean(PlatformKeyEncryption.class)).isNotNull();
        }
    }

    @Test
    void roundTripsWithAesGcmAndRejectsTampering() {
        MockEnvironment environment = new MockEnvironment().withProperty(
                "PLATFORM_SSL_ROOT_KEY", Base64.getEncoder().encodeToString(new byte[32]));
        PlatformKeyEncryption encryption = new PlatformKeyEncryption(environment);
        byte[] plaintext = "private-pkcs12".getBytes(StandardCharsets.UTF_8);

        EncryptedPayload encrypted = encryption.encrypt(plaintext);

        assertThat(encrypted.ciphertext()).isNotEqualTo(plaintext);
        assertThat(encryption.decrypt(encrypted.ciphertext(), encrypted.nonce())).isEqualTo(plaintext);
        encrypted.ciphertext()[0] ^= 1;
        assertThatThrownBy(() -> encryption.decrypt(encrypted.ciphertext(), encrypted.nonce()))
                .isInstanceOf(IllegalStateException.class)
                .hasRootCauseInstanceOf(AEADBadTagException.class);
    }

    @Test
    void requiresExactly256BitRootKey() {
        PlatformKeyEncryption encryption = new PlatformKeyEncryption(
                new MockEnvironment().withProperty("PLATFORM_SSL_ROOT_KEY",
                        Base64.getEncoder().encodeToString(new byte[16])));

        assertThatThrownBy(() -> encryption.encrypt(new byte[]{1}))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("exactly 32 bytes");
    }
}
