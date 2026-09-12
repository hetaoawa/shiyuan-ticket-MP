package top.hetao.shiyuanticketmp.express;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.test.util.ReflectionTestUtils;
import top.hetao.shiyuanticketmp.express.dto.ExpressTraceResponse;
import top.hetao.shiyuanticketmp.workorder.exception.WorkOrderException;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

class ExpressServiceTest {
    private final ObjectMapper mapper = new ObjectMapper();
    private final ExpressCodeRegistry registry = new ExpressCodeRegistry();
    private final ExpressService service = new ExpressService(registry, null, null, mapper, null);

    @Test
    void buildsGetWithSingleEncodedTrackingNumberAndMappedCarrier() {
        var request = service.buildRequest("https://wuliu.market.alicloudapi.com/kdi", "test-appcode",
                "SF123&other=1", "1234", "SF", 15);
        assertEquals("GET", request.method());
        assertTrue(request.bodyPublisher().isEmpty());
        assertEquals("APPCODE test-appcode", request.headers().firstValue("Authorization").orElseThrow());
        String[] params = request.uri().getRawQuery().split("&");
        assertEquals(2, params.length);
        assertEquals("no=SF123&other=1:1234", URLDecoder.decode(params[0], StandardCharsets.UTF_8));
        assertEquals("type=SFEXPRESS", params[1]);
    }

    @Test
    void unknownCarrierUsesProviderIdentificationAndPreservesConfiguredQuery() {
        for (String code : new String[]{null, "JTSD"}) {
            var request = service.buildRequest("https://example.com/kdi?tenant=demo", "test", "123", null, code, 5);
            assertEquals("tenant=demo&no=123", request.uri().getRawQuery());
        }
        assertFalse(registry.isMobileRequired("ZTO"));
        assertFalse(registry.isMobileRequired(null));
    }

    @ParameterizedTest
    @CsvSource({"SF,SFEXPRESS", "YD,YUNDA", "YZPY,CHINAPOST", "DBL,DEPPON", "DBKY,DEPPON",
            "KYE,KYEXPRESS", "KYSY,KYEXPRESS", "ZTOKY,ZTO56", "YDKY,YUNDA56", "ZTO,ZTO"})
    void mapsInternalCarrierCodes(String internalCode, String providerCode) {
        assertEquals(providerCode, registry.toProviderCode(internalCode));
        assertEquals(internalCode, registry.fromProviderCode(providerCode.toLowerCase(), internalCode));
    }

    @Test
    void mapsProviderResultAndKeepsCacheJsonContract() throws Exception {
        var response = service.parseResponse("""
                {"status":"0","result":{"number":"SF123:1234","type":"sfexpress","expName":"顺丰速运",
                "deliverystatus":"3","courierPhone":"123456","expSite":"https://example.com",
                "updateTime":"2026-09-12 10:00:00","list":[
                {"time":"2026-09-11 08:00:00","status":"已揽件"},
                {"time":"2026-09-12 08:00:00","status":"本人签收"}]}}
                """, "SF123", "SF");
        assertEquals("SF", response.getCpCode());
        assertEquals("SF123", response.getMailNo());
        assertEquals("顺丰速运", response.getLogisticsCompanyName());
        assertEquals("123456", response.getCpMobile());
        assertEquals("https://example.com", response.getCpUrl());
        assertEquals("2026-09-12 08:00:00", response.getTheLastTime());
        assertEquals("本人签收", response.getTheLastMessage());
        assertEquals(1789171200000L, response.getTraces().get(0).getTime());
        assertNull(response.getTraces().get(0).getLogisticsStatus());
        String json = mapper.writeValueAsString(response);
        assertTrue(mapper.readTree(json).has("logisticsTraceDetailList"));
        assertEquals(response, mapper.readValue(json, ExpressTraceResponse.class));
    }

    @ParameterizedTest
    @CsvSource({"0,ACCEPT", "1,TRANSPORT", "2,DELIVERING", "3,DELIVERED", "4,FAIL",
            "5,EXCEPTION", "6,RETURN", "99,UNKNOWN"})
    void mapsStatesAndOnlyDeliveredGetsPermanentCache(String providerStatus, String status) {
        var response = service.parseResponse("{\"status\":0,\"result\":{\"deliverystatus\":\""
                + providerStatus + "\",\"issign\":\"1\",\"list\":[]}}", "123", "ZTO");
        assertEquals(status, response.getLogisticsStatus());
        Object expiry = ReflectionTestUtils.invokeMethod(service, "calcExpiresAt", status);
        if ("DELIVERED".equals(status)) assertNull(expiry);
        else assertNotNull(expiry);
    }

    @Test
    void rejectsMissingSuccessFlagErrorsAndMalformedResults() {
        for (String body : new String[]{"{}", "{\"status\":null}", "{\"status\":0}",
                "{\"status\":0,\"result\":[]}", "{\"status\":0,\"result\":{}}", "not json"}) {
            assertThrows(WorkOrderException.class, () -> service.parseResponse(body, "123", "ZTO"));
        }
        var failure = assertThrows(WorkOrderException.class,
                () -> service.parseResponse("{\"status\":\"201\",\"msg\":\"单号不存在\"}", "123", "ZTO"));
        assertEquals("物流查询失败：单号不存在", failure.getMessage());
    }

    @Test
    void malformedTimePreservesTraceAndEmptyListUsesUpdateTime() {
        var result = service.parseResponse("""
                {"status":0,"result":{"type":"yunda","deliverystatus":1,"list":[
                {"time":"unexpected","status":"在途中"}]}}
                """, "123", null);
        assertEquals("YD", result.getCpCode());
        assertEquals("unexpected", result.getTheLastTime());
        assertNull(result.getTraces().get(0).getTime());
        var empty = service.parseResponse("""
                {"status":0,"result":{"deliverystatus":0,"updateTime":"2026-09-12 08:00:00","list":[]}}
                """, "123", null);
        assertTrue(empty.getTraces().isEmpty());
        assertEquals("2026-09-12 08:00:00", empty.getTheLastTime());
    }
}
