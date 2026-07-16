package top.hetao.shiyuanticketmp.platform.ssl.service;

public class InvalidDeployTokenException extends RuntimeException {
    public InvalidDeployTokenException() {
        super("Deploy token is invalid, expired, or revoked");
    }
}
