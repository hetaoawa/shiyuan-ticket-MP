package top.hetao.shiyuanticketmp.webhook.sender;

import org.springframework.web.util.UriComponentsBuilder;

final class WorkOrderDetailUrlBuilder {

    private WorkOrderDetailUrlBuilder() {
    }

    static String build(String baseUrl, Long workOrderId, String tenantCode) {
        if (baseUrl == null || baseUrl.isBlank()) {
            return null;
        }
        if (workOrderId == null || tenantCode == null || tenantCode.isBlank()) {
            throw new IllegalArgumentException("workOrderId and tenantCode are required");
        }
        return UriComponentsBuilder.fromUriString(baseUrl.trim())
                .pathSegment("workorder", "detail", workOrderId.toString())
                .queryParam("tenantCode", tenantCode)
                .build()
                .encode()
                .toUriString();
    }
}
