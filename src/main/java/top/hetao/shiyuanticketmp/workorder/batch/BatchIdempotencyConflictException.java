package top.hetao.shiyuanticketmp.workorder.batch;

public class BatchIdempotencyConflictException extends RuntimeException {
    public BatchIdempotencyConflictException(String message) {
        super(message);
    }
}
