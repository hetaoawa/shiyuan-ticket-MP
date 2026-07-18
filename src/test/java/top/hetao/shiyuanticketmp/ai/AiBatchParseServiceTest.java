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
        String text = "YT100 更址 张三 13800138000 广东省深圳市南山区1号\nSF200 intercept";
        when(model.parseBatch(text, "INTERCEPT")).thenReturn("""
                {"items":[
                  {"sourceLine":"YT100 更址 张三 13800138000 广东省深圳市南山区1号","title":"change - YT100","description":"YT100 更址 张三 13800138000 广东省深圳市南山区1号","trackingNo":"YT100","targetAddress":"张三，13800138000，广东省深圳市南山区1号","type":"CHANGE_ADDRESS","priority":1},
                  {"sourceLine":"SF200 intercept","title":"intercept - SF200","description":"SF200 intercept","trackingNo":"SF200","targetAddress":"","type":"INTERCEPT","priority":1}
                ]}
                """);

        JsonNode result = objectMapper.readTree(service.parseAndValidate(text, "INTERCEPT"));

        assertThat(result.path("type").asText()).isEqualTo("INTERCEPT");
        assertThat(result.path("items").size()).isEqualTo(2);
        assertThat(result.path("items").get(0).path("warnings").get(0).asText())
                .isEqualTo("TYPE_CONFLICT:CHANGE_ADDRESS");
        assertThat(result.path("items").get(0).path("title").asText())
                .isEqualTo("拦截工单 - YT100");
        assertThat(result.path("items").get(0).path("targetAddress").asText())
                .isEqualTo("张三，13800138000，广东省深圳市南山区1号");
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

    @Test
    void rejectsDescriptionThatChangesOriginalCriticalInformation() {
        String text = "YT100 改址到广东省深圳市南山区1号";
        when(model.parseBatch(text, null)).thenReturn("""
                {"items":[{"sourceLine":"YT100 改址到广东省深圳市南山区1号","title":"改址工单 - YT100",
                "description":"YT100 改址到广东省深圳市南山区2号","trackingNo":"YT100",
                "targetAddress":"广东省深圳市南山区1号","type":"CHANGE_ADDRESS","priority":1}]}
                """);

        assertThatThrownBy(() -> service.parseAndValidate(text, null))
                .isInstanceOf(AiParseService.AiParseException.class)
                .hasMessageContaining("description 未逐字保留原文");
    }

    @Test
    void validatesChangeAddressUsingExpectedEffectiveType() {
        String text = "YT100 更址 张三 13800138000 广东省深圳市南山区1号";
        when(model.parseBatch(text, "CHANGE_ADDRESS")).thenReturn("""
                {"items":[{"sourceLine":"YT100 更址 张三 13800138000 广东省深圳市南山区1号",
                "title":"intercept - YT100","description":"YT100 更址 张三 13800138000 广东省深圳市南山区1号",
                "trackingNo":"YT100","targetAddress":"","type":"INTERCEPT","priority":1}]}
                """);

        assertThatThrownBy(() -> service.parseAndValidate(text, "CHANGE_ADDRESS"))
                .isInstanceOfSatisfying(AiParseService.AiParseException.class,
                        error -> assertThat(error.isRetryable()).isTrue());
    }

    @Test
    void doesNotApplyChangeAddressShapeWhenExpectedTypeOverridesModelType() throws Exception {
        String text = "YT100 intercept";
        when(model.parseBatch(text, "INTERCEPT")).thenReturn("""
                {"items":[{"sourceLine":"YT100 intercept","title":"intercept - YT100",
                "description":"YT100 intercept","trackingNo":"YT100","targetAddress":"",
                "type":"CHANGE_ADDRESS","priority":1}]}
                """);

        JsonNode result = objectMapper.readTree(service.parseAndValidate(text, "INTERCEPT"));

        assertThat(result.path("items").get(0).path("targetAddress").asText()).isEmpty();
        assertThat(result.path("items").get(0).path("warnings").get(0).asText())
                .isEqualTo("TYPE_CONFLICT:CHANGE_ADDRESS");
    }

    @Test
    void normalizesSynonymousChangeAddressTitlesToOneServerDefinedFormat() throws Exception {
        String first = "YT100 更址 张三 13800138000 广东省深圳市南山区1号";
        String second = "SF200 改地址 李四 13900139000 广东省深圳市福田区2号";
        String text = first + "\n" + second;
        when(model.parseBatch(text, "CHANGE_ADDRESS")).thenReturn("""
                {"items":[
                  {"sourceLine":"YT100 更址 张三 13800138000 广东省深圳市南山区1号","title":"更址 - YT100","description":"YT100 更址 张三 13800138000 广东省深圳市南山区1号","trackingNo":"YT100","targetAddress":"张三, 13800138000, 广东省深圳市南山区1号","type":"CHANGE_ADDRESS","priority":1},
                  {"sourceLine":"SF200 改地址 李四 13900139000 广东省深圳市福田区2号","title":"改地址工单：SF200","description":"SF200 改地址 李四 13900139000 广东省深圳市福田区2号","trackingNo":"SF200","targetAddress":"李四，13900139000，广东省深圳市福田区2号","type":"CHANGE_ADDRESS","priority":1}
                ]}
                """);

        JsonNode result = objectMapper.readTree(service.parseAndValidate(text, "CHANGE_ADDRESS"));

        assertThat(result.path("items").get(0).path("title").asText())
                .isEqualTo("更址工单 - YT100");
        assertThat(result.path("items").get(1).path("title").asText())
                .isEqualTo("更址工单 - SF200");
    }

    @Test
    void batchInputMayContainMultipleTrackingNumbersInOneLine() {
        assertThat(service.validateInput("请处理 YT100 和 SF200", null))
                .containsExactly("请处理 YT100 和 SF200");
    }
}
