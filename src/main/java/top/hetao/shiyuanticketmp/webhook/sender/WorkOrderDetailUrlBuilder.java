package top.hetao.shiyuanticketmp.webhook.sender;

import org.springframework.web.util.UriComponentsBuilder;
import org.springframework.web.util.UriUtils;

import java.net.URI;

import static java.nio.charset.StandardCharsets.UTF_8;

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

        URI baseUri = parseBaseUri(baseUrl.trim());
        String rawPath = baseUri.getRawPath() == null ? "" : baseUri.getRawPath();
        rawPath = rawPath.replaceFirst("/+$", "");
        String detailPath = rawPath + "/workorder/detail/" + workOrderId;

        String tenantQuery = "tenantCode=" + UriUtils.encodeQueryParam(tenantCode, UTF_8);
        String rawQuery = baseUri.getRawQuery();
        String detailQuery = rawQuery == null || rawQuery.isEmpty()
                ? tenantQuery : rawQuery + "&" + tenantQuery;

        return UriComponentsBuilder.fromUri(baseUri)
                .replacePath(detailPath)
                .replaceQuery(detailQuery)
                .build(true)
                .toUriString();
    }

    private static URI parseBaseUri(String baseUrl) {
        try {
            return URI.create(baseUrl);
        } catch (IllegalArgumentException ignored) {
            return UriComponentsBuilder.fromUriString(baseUrl)
                    .build()
                    .encode()
                    .toUri();
        }
    }
}
