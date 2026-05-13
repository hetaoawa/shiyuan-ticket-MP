package top.hetao.shiyuanticketmp.workorder.service;

import org.springframework.stereotype.Component;
import top.hetao.shiyuanticketmp.workorder.enums.WorkOrderType;

import java.util.Map;

/**
 * 工单类型自动解析器。
 *
 * <p>根据工单标题和描述中的关键词自动识别工单类型。
 * 使用简单的关键词匹配策略，可扩展为 NLP/LLM 智能分类。
 */
@Component
public class WorkOrderTypeResolver {

    private static final Map<WorkOrderType, String[]> KEYWORDS = Map.of(
            WorkOrderType.DAMAGE,   new String[]{"破损", "损坏", "破了", "碎了", "坏了"},
            WorkOrderType.LOSS,     new String[]{"丢失", "丢了", "遗失", "找不到", "少了"},
            WorkOrderType.DELAY,    new String[]{"延迟", "迟了", "晚了", "超时", "逾期"},
            WorkOrderType.INVENTORY, new String[]{"库存", "盘点", "多货", "少货", "差异"},
            WorkOrderType.SHIPPING, new String[]{"发货", "出库", "寄出", "配送", "快递"},
            WorkOrderType.RETURN,   new String[]{"退货", "退回", "返仓", "退款", "换货"}
    );

    /**
     * 根据标题和描述解析工单类型。
     *
     * @param title       工单标题
     * @param description 工单描述
     * @return 匹配的工单类型，无匹配时返回 OTHER
     */
    public WorkOrderType resolve(String title, String description) {
        String text = (title != null ? title : "") + " " + (description != null ? description : "");

        for (Map.Entry<WorkOrderType, String[]> entry : KEYWORDS.entrySet()) {
            for (String keyword : entry.getValue()) {
                if (text.contains(keyword)) {
                    return entry.getKey();
                }
            }
        }

        return WorkOrderType.OTHER;
    }
}
