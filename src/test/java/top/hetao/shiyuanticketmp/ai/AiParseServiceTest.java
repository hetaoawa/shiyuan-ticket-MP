package top.hetao.shiyuanticketmp.ai;

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
                .contains("地址信息不完整属于业务核实问题，必须返回 422");
        assertThat(AiParseService.BATCH_SYSTEM_PROMPT)
                .contains("sourceLine 和 description 都必须逐字复制对应原文")
                .contains("第N条地址缺少区县信息，请用户核实")
                .contains("不得因为缺少地址而拒绝拦截、破损、丢失等工单");
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
