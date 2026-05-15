package top.hetao.shiyuanticketmp.workorder.enums;

/**
 * 工单类型枚举。
 *
 * <p>根据工单标题/描述中的关键词自动识别。
 */
public enum WorkOrderType {

    /** 改地址 */
    CHANGE_ADDRESS("改地址"),

    /** 拦截 */
    INTERCEPT("拦截"),

    /** 破损 */
    DAMAGE("破损"),

    /** 丢失 */
    LOST("丢失"),

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
