package top.hetao.shiyuanticketmp.webhook.sender;

/** Immutable result of a synchronous channel delivery attempt. */
public record DispatchResult(boolean success, Integer statusCode, String message) {

    public static DispatchResult succeeded(int statusCode) {
        return new DispatchResult(true, statusCode, "投递成功");
    }

    public static DispatchResult failed(Integer statusCode, String message) {
        return new DispatchResult(false, statusCode,
                message == null || message.isBlank() ? "投递失败" : message);
    }
}
