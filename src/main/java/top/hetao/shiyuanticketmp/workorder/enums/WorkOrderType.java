package top.hetao.shiyuanticketmp.workorder.enums;

/**
 * 工单类型枚举。
 *
 * <p>根据工单标题/描述中的关键词自动识别。
 */
public enum WorkOrderType {

    /** 破损反馈 */
    DAMAGE("破损反馈"),

    /** 丢失反馈 */
    LOSS("丢失反馈"),

    /** 延迟反馈 */
    DELAY("延迟反馈"),

    /** 库存异常 */
    INVENTORY("库存异常"),

    /** 发货请求 */
    SHIPPING("发货请求"),

    /** 退货处理 */
    RETURN("退货处理"),

    /** 其他 */
    OTHER("其他");

    private final String label;

    WorkOrderType(String label) {
        this.label = label;
    }

    public String getLabel() {
        return label;
    }
}
