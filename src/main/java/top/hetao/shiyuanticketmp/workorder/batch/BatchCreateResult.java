package top.hetao.shiyuanticketmp.workorder.batch;

import java.util.List;

public record BatchCreateResult(List<Long> workOrderIds, boolean replayed) {
}
