package top.hetao.shiyuanticketmp.platform.ssl.controller;

import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import top.hetao.shiyuanticketmp.platform.ssl.service.InvalidDeployTokenException;
import top.hetao.shiyuanticketmp.platform.ssl.service.PlatformSslException;

import java.util.Map;

@Order(0)
@RestControllerAdvice(assignableTypes = {PlatformSslAdminController.class, PlatformSslDeployController.class})
public class PlatformSslExceptionHandler {

    @ExceptionHandler(PlatformSslException.class)
    public ResponseEntity<Map<String, Object>> badRequest(PlatformSslException exception) {
        return ResponseEntity.badRequest().body(Map.of("code", 400, "message", exception.getMessage()));
    }

    @ExceptionHandler(InvalidDeployTokenException.class)
    public ResponseEntity<Map<String, Object>> unauthorized(InvalidDeployTokenException exception) {
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                .body(Map.of("code", 401, "message", exception.getMessage()));
    }
}
