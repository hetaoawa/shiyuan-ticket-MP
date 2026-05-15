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
            WorkOrderType.CHANGE_ADDRESS, new String[]{"改地址", "修改地址", "变更地址", "地址变更"},
            WorkOrderType.INTERCEPT,      new String[]{"拦截", "拦截件", "中途拦截"},
            WorkOrderType.DAMAGE,         new String[]{"破损", "损坏", "破了", "碎了", "坏了"},
            WorkOrderType.LOST,           new String[]{"丢失", "丢了", "遗失", "找不到", "少了"}
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
