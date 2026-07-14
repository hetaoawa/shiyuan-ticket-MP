package top.hetao.shiyuanticketmp.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AiBatchParseServiceTest {
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final AiParseService model = mock(AiParseService.class);
    private final AiBatchParseService service = new AiBatchParseService(model, objectMapper);

    @Test
    void validatesOneResponseAndMovesUnifiedTypeToTopLevel() throws Exception {
        String text = "YT100 change address\nSF200 intercept";
        when(model.parseBatch(text, "INTERCEPT")).thenReturn("""
                {"items":[
                  {"sourceLine":"YT100 change address","title":"change - YT100","description":"d1","trackingNo":"YT100","targetAddress":"new","type":"CHANGE_ADDRESS","priority":1},
                  {"sourceLine":"SF200 intercept","title":"intercept - SF200","description":"d2","trackingNo":"SF200","targetAddress":"","type":"INTERCEPT","priority":1}
                ]}
                """);

        JsonNode result = objectMapper.readTree(service.parseAndValidate(text, "INTERCEPT"));

        assertThat(result.path("type").asText()).isEqualTo("INTERCEPT");
        assertThat(result.path("items").size()).isEqualTo(2);
        assertThat(result.path("items").get(0).path("warnings").get(0).asText())
                .isEqualTo("TYPE_CONFLICT:CHANGE_ADDRESS");
        assertThat(result.path("items").get(1).path("warnings").isEmpty()).isTrue();
    }

    @Test
    void rejectsDuplicateLinesBeforeCallingModel() {
        assertThatThrownBy(() -> service.parseAndValidate("YT100\nyt100", null))
                .isInstanceOf(AiParseService.AiParseException.class)
                .hasMessageContaining("不能重复");
    }

    @Test
    void rejectsHallucinatedTrackingNumberAndUnknownSchemaFields() {
        String text = "YT100 intercept";
        when(model.parseBatch(text, null)).thenReturn("""
                {"items":[{"sourceLine":"YT100 intercept","title":"x","description":"x",
                "trackingNo":"FAKE","targetAddress":"","type":"INTERCEPT","priority":1,"extra":true}]}
                """);

        assertThatThrownBy(() -> service.parseAndValidate(text, null))
                .isInstanceOf(AiParseService.AiParseException.class)
                .hasMessageContaining("字段必须严格");
    }
}
