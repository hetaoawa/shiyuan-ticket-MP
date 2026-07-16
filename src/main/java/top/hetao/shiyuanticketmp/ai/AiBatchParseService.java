package top.hetao.shiyuanticketmp.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.stereotype.Service;
import top.hetao.shiyuanticketmp.workorder.enums.WorkOrderType;

import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

@Service
public class AiBatchParseService {
    public static final int MAX_ITEMS = 20;
    public static final int MAX_TEXT_LENGTH = 2000;
    public static final String SCHEMA_VERSION = "v2";
    private static final Set<String> ROOT_FIELDS = Set.of("items");
    private static final Set<String> ITEM_FIELDS = Set.of(
            "sourceLine", "title", "description", "trackingNo",
            "targetAddress", "type", "priority");

    private final AiParseService aiParseService;
    private final ObjectMapper objectMapper;

    public AiBatchParseService(AiParseService aiParseService, ObjectMapper objectMapper) {
        this.aiParseService = aiParseService;
        this.objectMapper = objectMapper;
    }

    public String parseAndValidate(String text, String expectedType) {
        List<String> lines = validateInput(text, expectedType);
        String raw = aiParseService.parseBatch(String.join("\n", lines), expectedType);
        return validateModelResult(raw, lines, expectedType).toString();
    }

    List<String> validateInput(String text, String expectedType) {
        if (text == null || text.isBlank()) {
            throw new AiParseService.AiParseException("text 不能为空");
        }
        if (text.length() > MAX_TEXT_LENGTH) {
            throw new AiParseService.AiParseException("text 不能超过 2000 字符");
        }
        validateType(expectedType, "expectedType");
        List<String> lines = text.lines().map(String::trim).filter(line -> !line.isEmpty()).toList();
        if (lines.isEmpty() || lines.size() > MAX_ITEMS) {
            throw new AiParseService.AiParseException("批量解析必须包含 1-20 条非空内容");
        }
        Set<String> unique = new HashSet<>();
        for (String line : lines) {
            if (!unique.add(line.toLowerCase(Locale.ROOT))) {
                throw new AiParseService.AiParseException("批量解析内容不能重复: " + line);
            }
        }
        return lines;
    }

    ObjectNode validateModelResult(String raw, List<String> lines, String expectedType) {
        try {
            JsonNode root = objectMapper.readTree(raw);
            requireObjectWithExactFields(root, ROOT_FIELDS, "root");
            JsonNode items = root.get("items");
            if (!items.isArray() || items.size() != lines.size()
                    || items.isEmpty() || items.size() > MAX_ITEMS) {
                throw schemaError("items 数量必须与输入一致且为 1-20");
            }

            ArrayNode outputItems = objectMapper.createArrayNode();
            Set<String> sourceLines = new HashSet<>();
            Set<String> rawTypes = new HashSet<>();
            for (int i = 0; i < items.size(); i++) {
                JsonNode item = items.get(i);
                requireObjectWithExactFields(item, ITEM_FIELDS, "items[" + i + "]");
                String sourceLine = requiredText(item, "sourceLine", i, Integer.MAX_VALUE);
                if (!sourceLine.equals(lines.get(i))) {
                    throw schemaError("items[" + i + "].sourceLine 与原文不一致");
                }
                if (!sourceLines.add(sourceLine.toLowerCase(Locale.ROOT))) {
                    throw schemaError("sourceLine 重复");
                }
                requiredText(item, "title", i, 200);
                String description = optionalText(item, "description", i, Integer.MAX_VALUE);
                if (!description.equals(sourceLine)) {
                    throw schemaError("items[" + i + "].description 未逐字保留原文");
                }
                String trackingNo = optionalText(item, "trackingNo", i, 50);
                optionalText(item, "targetAddress", i, 500);
                String type = requiredText(item, "type", i, 30);
                validateType(type, "items[" + i + "].type");
                rawTypes.add(type);
                JsonNode priority = item.get("priority");
                if (!priority.isIntegralNumber() || priority.intValue() < 1 || priority.intValue() > 3) {
                    throw schemaError("items[" + i + "].priority 必须为 1、2 或 3");
                }
                if (!trackingNo.isBlank()
                        && !sourceLine.toLowerCase(Locale.ROOT).contains(trackingNo.toLowerCase(Locale.ROOT))) {
                    throw schemaError("items[" + i + "].trackingNo 不存在于原文");
                }

                ObjectNode out = item.deepCopy();
                ArrayNode warnings = out.putArray("warnings");
                if (expectedType != null && !expectedType.isBlank() && !expectedType.equals(type)) {
                    warnings.add("TYPE_CONFLICT:" + type);
                }
                outputItems.add(out);
            }

            ObjectNode output = objectMapper.createObjectNode();
            if (expectedType != null && !expectedType.isBlank()) {
                output.put("type", expectedType);
            } else if (rawTypes.size() == 1) {
                output.put("type", rawTypes.iterator().next());
            } else {
                output.putNull("type");
            }
            output.set("items", outputItems);
            return output;
        } catch (AiParseService.AiParseException e) {
            throw e;
        } catch (Exception e) {
            throw schemaError("AI 返回不是合法 JSON");
        }
    }

    private void requireObjectWithExactFields(JsonNode node, Set<String> expected, String path) {
        if (!node.isObject()) {
            throw schemaError(path + " 必须是对象");
        }
        Set<String> actual = new HashSet<>();
        node.fieldNames().forEachRemaining(actual::add);
        if (!actual.equals(expected)) {
            throw schemaError(path + " 字段必须严格为 " + expected);
        }
    }

    private String requiredText(JsonNode item, String field, int index, int max) {
        String value = optionalText(item, field, index, max);
        if (value.isBlank()) {
            throw schemaError("items[" + index + "]." + field + " 不能为空");
        }
        return value;
    }

    private String optionalText(JsonNode item, String field, int index, int max) {
        JsonNode value = item.get(field);
        if (value == null || !value.isTextual()) {
            throw schemaError("items[" + index + "]." + field + " 必须是字符串");
        }
        if (value.textValue().length() > max) {
            throw schemaError("items[" + index + "]." + field + " 过长");
        }
        return value.textValue();
    }

    private void validateType(String type, String path) {
        if (type == null || type.isBlank()) {
            return;
        }
        try {
            WorkOrderType.valueOf(type);
        } catch (IllegalArgumentException e) {
            throw schemaError(path + " 非法: " + type);
        }
    }

    private AiParseService.AiParseException schemaError(String message) {
        return new AiParseService.AiParseException(message);
    }
}
