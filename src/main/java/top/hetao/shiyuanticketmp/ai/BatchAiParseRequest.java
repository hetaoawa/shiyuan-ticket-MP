package top.hetao.shiyuanticketmp.ai;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import lombok.Data;

@Data
public class BatchAiParseRequest {
    private String text;
    private String expectedType;

    @JsonAnySetter
    public void rejectUnknownField(String name, Object value) {
        throw new IllegalArgumentException("批量 AI 解析不接受字段: " + name);
    }
}
