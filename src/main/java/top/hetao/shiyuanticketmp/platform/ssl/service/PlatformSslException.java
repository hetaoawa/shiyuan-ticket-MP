package top.hetao.shiyuanticketmp.platform.ssl.service;

public class PlatformSslException extends RuntimeException {
    public PlatformSslException(String message) {
        super(message);
    }

    public PlatformSslException(String message, Throwable cause) {
        super(message, cause);
    }
}
