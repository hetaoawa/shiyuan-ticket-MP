package top.hetao.shiyuanticketmp.common.exception;

import cn.dev33.satoken.exception.NotLoginException;
import cn.dev33.satoken.exception.NotPermissionException;
import cn.dev33.satoken.exception.NotRoleException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import top.hetao.shiyuanticketmp.workorder.exception.WorkOrderException;

import java.util.Map;

/**
 * 全局异常处理器，统一捕获并转换为标准 JSON 响应。
 *
 * <p>处理的异常类型：
 * <ul>
 *   <li>{@link WorkOrderException} — 业务异常，返回 400</li>
 *   <li>{@link NotLoginException} — Sa-Token 未登录，返回 401</li>
 *   <li>{@link NotRoleException} — Sa-Token 角色不足，返回 403</li>
 *   <li>{@link NotPermissionException} — Sa-Token 权限不足，返回 403</li>
 *   <li>{@link Exception} — 未知异常，返回 500</li>
 * </ul>
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    /** 业务异常 → 400 */
    @ExceptionHandler(WorkOrderException.class)
    public ResponseEntity<Map<String, Object>> handleWorkOrderException(WorkOrderException e) {
        return ResponseEntity.badRequest().body(Map.of(
                "code", 400,
                "message", e.getMessage()
        ));
    }

    /** Sa-Token 未登录 → 401 */
    @ExceptionHandler(NotLoginException.class)
    public ResponseEntity<Map<String, Object>> handleNotLoginException(NotLoginException e) {
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of(
                "code", 401,
                "message", "未登录或登录已过期"
        ));
    }

    /** Sa-Token 角色不足 → 403 */
    @ExceptionHandler(NotRoleException.class)
    public ResponseEntity<Map<String, Object>> handleNotRoleException(NotRoleException e) {
        return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of(
                "code", 403,
                "message", "角色权限不足: " + e.getRole()
        ));
    }

    /** Sa-Token 权限不足 → 403 */
    @ExceptionHandler(NotPermissionException.class)
    public ResponseEntity<Map<String, Object>> handleNotPermissionException(NotPermissionException e) {
        return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of(
                "code", 403,
                "message", "操作权限不足: " + e.getPermission()
        ));
    }

    /** 未知异常 → 500 */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, Object>> handleException(Exception e) {
        log.error("未知异常", e);
        return ResponseEntity.internalServerError().body(Map.of(
                "code", 500,
                "message", "服务器内部错误"
        ));
    }
}
