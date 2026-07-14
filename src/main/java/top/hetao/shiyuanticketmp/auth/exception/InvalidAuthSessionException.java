package top.hetao.shiyuanticketmp.auth.exception;

/** Raised when a logged-in token contains an invalid or stale tenant identity. */
public class InvalidAuthSessionException extends RuntimeException {

    public InvalidAuthSessionException(String message) {
        super(message);
    }

    public InvalidAuthSessionException(String message, Throwable cause) {
        super(message, cause);
    }
}
