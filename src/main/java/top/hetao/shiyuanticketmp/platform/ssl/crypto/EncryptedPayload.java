package top.hetao.shiyuanticketmp.platform.ssl.crypto;

public record EncryptedPayload(byte[] ciphertext, byte[] nonce) {
}
