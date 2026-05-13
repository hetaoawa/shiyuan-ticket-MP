package top.hetao.shiyuanticketmp.common.exception;

import cn.dev33.satoken.exception.NotLoginException;
import cn.dev33.satoken.exception.NotPermissionException;
import cn.dev33.satoken.exception.NotRoleException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import top.hetao.shiyuanticketmp.workorder.exception.WorkOrderException;

import java.util.Map;

/**
 * 全局异常处理器，统一捕获并转换为标准 JSON 响应。
 *
 * <p>响应格式统一为 {@code {"code": int, "message": "..."}}，
 * 前端拦截器通过 {@code res.code} 判断成功/失败。
 *
 * <p>处理的异常类型：
 * <ul>
 *   <li>{@link WorkOrderException} — 业务异常，返回 400</li>
 *   <li>{@link NotLoginException} — Sa-Token 未登录，返回 401</li>
 *   <li>{@link NotRoleException} — Sa-Token 角色不足，返回 403</li>
 *   <li>{@link NotPermissionException} — Sa-Token 权限不足，返回 403</li>
 *   <li>Spring MVC 参数/请求异常 — 返回 400</li>
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

    /** 缺少请求参数 → 400 */
    @ExceptionHandler(MissingServletRequestParameterException.class)
    public ResponseEntity<Map<String, Object>> handleMissingParam(MissingServletRequestParameterException e) {
        return ResponseEntity.badRequest().body(Map.of(
                "code", 400,
                "message", "缺少请求参数: " + e.getParameterName()
        ));
    }

    /** 请求体无法解析（JSON 格式错误等） → 400 */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<Map<String, Object>> handleNotReadable(HttpMessageNotReadableException e) {
        return ResponseEntity.badRequest().body(Map.of(
                "code", 400,
                "message", "请求体格式错误"
        ));
    }

    /** 请求方法不支持 → 405 */
    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<Map<String, Object>> handleMethodNotAllowed(HttpRequestMethodNotSupportedException e) {
        return ResponseEntity.status(HttpStatus.METHOD_NOT_ALLOWED).body(Map.of(
                "code", 405,
                "message", "请求方法不支持: " + e.getMethod()
        ));
    }

    /** 参数校验失败（@Valid） → 400 */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Map<String, Object>> handleValidation(MethodArgumentNotValidException e) {
        String msg = e.getBindingResult().getFieldErrors().stream()
                .map(fe -> fe.getField() + ": " + fe.getDefaultMessage())
                .reduce((a, b) -> a + "; " + b)
                .orElse("参数校验失败");
        return ResponseEntity.badRequest().body(Map.of(
                "code", 400,
                "message", msg
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
