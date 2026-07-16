package top.hetao.shiyuanticketmp.platform.ssl.repository;

import java.time.LocalDateTime;

public record PlatformSslOperationView(
        long id,
        String operationType,
        String status,
        boolean fromHttps,
        boolean toHttps,
        Long certificateVersionId,
        String message,
        Long operatorId,
        LocalDateTime createdAt,
        LocalDateTime completedAt) {
}
