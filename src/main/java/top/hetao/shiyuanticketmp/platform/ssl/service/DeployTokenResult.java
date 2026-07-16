package top.hetao.shiyuanticketmp.platform.ssl.service;

import java.time.LocalDateTime;

public record DeployTokenResult(long id, String token, LocalDateTime expiresAt) {
}
