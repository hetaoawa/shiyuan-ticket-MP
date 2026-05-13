package top.hetao.shiyuanticketmp.workorder.exception;

/**
 * 工单业务异常
 *
 * <p>用于表示工单状态流转非法、工单不存在等业务级错误，
 * 上层 GlobalExceptionHandler 捕获后统一返回 4xx 响应。
 */
public class WorkOrderException extends RuntimeException {

    public WorkOrderException(String message) {
        super(message);
    }

    public WorkOrderException(String message, Throwable cause) {
        super(message, cause);
    }
}
