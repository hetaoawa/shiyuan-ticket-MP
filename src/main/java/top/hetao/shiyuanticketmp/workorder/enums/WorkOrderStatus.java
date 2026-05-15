package top.hetao.shiyuanticketmp.workorder.enums;

/**
 * 工单状态枚举
 *
 * <p>有效流转路径：
 * <pre>
 *  PENDING（待处理）
 *      │
 *      ▼  assign()
 *  IN_PROGRESS（处理中）
 *      │                    │
 *      ▼  close()           ▼  reject()
 *  CLOSED（已关闭）     REJECTED（已驳回）
 *                          │
 *                          ▼  resubmit()  ← 提交人编辑后重新提交
 *                      PENDING（待处理）
 *
 *  管理员强制驳回：任意非 CLOSED → REJECTED（forceReject）
 * </pre>
 */
public enum WorkOrderStatus {
    /** 待派发，刚创建的初始状态 */
    PENDING,
    /** 已派发，处理人处理中 */
    IN_PROGRESS,
    /** 已关闭，处理完成（可触发计费等下游动作） */
    CLOSED,
    /** 已驳回，终态，不可再流转 */
    REJECTED
}
