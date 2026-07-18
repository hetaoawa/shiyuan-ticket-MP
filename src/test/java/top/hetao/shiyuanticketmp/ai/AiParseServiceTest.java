package top.hetao.shiyuanticketmp.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import top.hetao.shiyuanticketmp.tenant.integration.TenantIntegrationResolver;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

class AiParseServiceTest {
    private final AiParseService service = new AiParseService(
            new ObjectMapper(), mock(TenantIntegrationResolver.class));

    @Test
    void promptLimitsAddressCompletionAndPreservesCriticalInformation() {
        assertThat(AiParseService.SYSTEM_PROMPT)
                .contains("description 必须逐字复制用户输入全文")
                .contains("省和区/县均已明确、仅缺少市")
                .contains("若地址已写明省和市、但没有区/县，禁止虚构区/县")
                .contains("不得因为缺少地址而拒绝拦截、破损、丢失等工单")
                .contains("姓名, 电话, 地址")
                .contains("更址三段组合及固定分隔符“, ”")
                .contains(AiParseService.MULTIPLE_TRACKING_NUMBERS_MESSAGE)
                .contains("地址信息不完整属于业务核实问题，必须返回 422");
        assertThat(AiParseService.BATCH_SYSTEM_PROMPT)
                .contains("sourceLine 和 description 都必须逐字复制对应原文")
                .contains("第N条地址缺少区县信息，请用户核实")
                .contains("第N条更址工单缺少姓名、电话或地址")
                .contains("同一 type 不得使用同义词或不同标题模板")
                .contains("不得因为缺少地址而拒绝拦截、破损、丢失等工单");
        assertThat(AiParseService.SCHEMA_VERSION).isEqualTo("v4");
        assertThat(AiBatchParseService.SCHEMA_VERSION).isEqualTo("v4");
    }

    @Test
    void addressVerificationResponseKeepsSpecificMessageAndIsNotAnInvalidInput() {
        assertThatThrownBy(() -> service.validateControlResponse(
                "{\"code\":422,\"msg\":\"地址缺少区县信息，请用户核实\"}"))
                .isInstanceOfSatisfying(AiParseService.AiParseException.class, error -> {
                    assertThat(error).hasMessage("地址缺少区县信息，请用户核实");
                    assertThat(error.isInvalidInput()).isFalse();
                    assertThat(error.isRetryable()).isFalse();
                });
    }

    @Test
    void maliciousInputResponseRemainsARejectedInvalidInput() {
        assertThatThrownBy(() -> service.validateControlResponse(
                "{\"code\":418,\"msg\":\"不合法的输入\"}"))
                .isInstanceOfSatisfying(AiParseService.AiParseException.class, error -> {
                    assertThat(error).hasMessage("不合法的输入");
                    assertThat(error.isInvalidInput()).isTrue();
                    assertThat(error.isRetryable()).isFalse();
                });
    }

    @Test
    void explicitMissingChangeAddressFieldsRemainsNonRetryableBusinessValidation() {
        assertThatThrownBy(() -> service.validateControlResponse(
                "{\"code\":422,\"msg\":\"更址工单缺少姓名、电话或地址，请用户核实\"}"))
                .isInstanceOfSatisfying(AiParseService.AiParseException.class, error -> {
                    assertThat(error).hasMessage("更址工单缺少姓名、电话或地址，请用户核实");
                    assertThat(error.isInvalidInput()).isFalse();
                    assertThat(error.isRetryable()).isFalse();
                });
    }

    @Test
    void rejectsMultipleTrackingNumbersAsNonRetryableBusinessValidation() {
        assertThatThrownBy(() -> AiParseService.validateSingleTrackingNumberInput(
                "请拦截 YT100 和 SF200 两个包裹"))
                .isInstanceOfSatisfying(AiParseService.AiParseException.class, error -> {
                    assertThat(error).hasMessage(AiParseService.MULTIPLE_TRACKING_NUMBERS_MESSAGE);
                    assertThat(error.isInvalidInput()).isFalse();
                    assertThat(error.isRetryable()).isFalse();
                });

        assertThatThrownBy(() -> AiParseService.validateSingleTrackingNumberInput(
                "运单号 123456789012、987654321098 均需拦截"))
                .isInstanceOf(AiParseService.AiParseException.class)
                .hasMessage(AiParseService.MULTIPLE_TRACKING_NUMBERS_MESSAGE);
    }

    @Test
    void allowsRepeatedSameTrackingNumberAndDoesNotTreatPhoneAsAnotherTrackingNumber() {
        AiParseService.validateSingleTrackingNumberInput(
                "YT100 更址，YT100 的新收件人电话为 13800138000");
    }

    @Test
    void normalizesChangeAddressSeparatorsAndPreservesThreeSourceParts() throws Exception {
        String input = "YT100 更址，收件人张三，电话13800138000，地址广东省深圳市南山区1号";
        String normalized = service.validateAndNormalizeSingleModelResult("""
                {"title":"更址工单 - YT100","description":"YT100 更址，收件人张三，电话13800138000，地址广东省深圳市南山区1号",
                "trackingNo":"YT100","targetAddress":"张三，13800138000，广东省深圳市南山区1号",
                "type":"CHANGE_ADDRESS","priority":1}
                """, input);

        JsonNode result = new ObjectMapper().readTree(normalized);
        assertThat(result.path("targetAddress").asText())
                .isEqualTo("张三, 13800138000, 广东省深圳市南山区1号");
    }

    @Test
    void malformedOrHallucinatedChangeAddressOutputIsRetryable() {
        String input = "YT100 更址，收件人张三，电话13800138000，地址广东省深圳市南山区1号";

        assertThatThrownBy(() -> service.validateAndNormalizeSingleModelResult("""
                {"title":"更址工单 - YT100","description":"YT100 更址，收件人张三，电话13800138000，地址广东省深圳市南山区1号",
                "trackingNo":"YT100","targetAddress":"张三 广东省深圳市南山区1号",
                "type":"CHANGE_ADDRESS","priority":1}
                """, input))
                .isInstanceOfSatisfying(AiParseService.AiParseException.class,
                        error -> assertThat(error.isRetryable()).isTrue());

        assertThatThrownBy(() -> service.validateAndNormalizeSingleModelResult("""
                {"title":"更址工单 - YT100","description":"YT100 更址，收件人张三，电话13800138000，地址广东省深圳市南山区1号",
                "trackingNo":"YT100","targetAddress":"张三, 13999999999, 广东省深圳市南山区1号",
                "type":"CHANGE_ADDRESS","priority":1}
                """, input))
                .isInstanceOfSatisfying(AiParseService.AiParseException.class,
                        error -> assertThat(error.isRetryable()).isTrue());
    }

    @Test
    void singleResultRejectsChangedDescriptionAndHallucinatedTrackingNumber() {
        String input = "YT100 改址到广东省深圳市南山区1号";

        assertThatThrownBy(() -> service.validateSingleModelResult("""
                {"description":"YT100 改址到广东省深圳市南山区2号","trackingNo":"YT100"}
                """, input))
                .isInstanceOf(AiParseService.AiParseException.class)
                .hasMessage("AI 返回的 description 未逐字保留原文");

        assertThatThrownBy(() -> service.validateSingleModelResult("""
                {"description":"YT100 改址到广东省深圳市南山区1号","trackingNo":"FAKE"}
                """, input))
                .isInstanceOf(AiParseService.AiParseException.class)
                .hasMessage("AI 返回的 trackingNo 不存在于原文");
    }
}
