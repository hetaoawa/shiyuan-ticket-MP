package top.hetao.shiyuanticketmp.webhook.sender;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WorkOrderDetailUrlBuilderTest {

    @Test
    void preservesExistingQueryWhenAppendingTenantCode() {
        assertEquals(
                "https://example.test/base/workorder/detail/123?source=push&tenantCode=tenant-100",
                WorkOrderDetailUrlBuilder.build(
                        "https://example.test/base?source=push", 123L, "tenant-100"));
    }

    @Test
    void preservesExistingRawEncodingWhenAppendingEncodedTenantCode() {
        assertEquals(
                "https://example.test/base%20path/workorder/detail/123"
                        + "?x=a%20b&tenantCode=tenant%20%26%20one",
                WorkOrderDetailUrlBuilder.build(
                        "https://example.test/base%20path?x=a%20b", 123L, "tenant & one"));
    }

    @Test
    void removesAllTrailingPathSlashesBeforeAppendingDetailPath() {
        assertEquals(
                "https://example.test/base/workorder/detail/123?x=1&tenantCode=tenant-100",
                WorkOrderDetailUrlBuilder.build(
                        "https://example.test/base///?x=1", 123L, "tenant-100"));
    }

    @Test
    void encodesPathAndTenantCode() {
        assertEquals(
                "https://example.test/base%20path/workorder/detail/123?tenantCode=tenant%20%26%20one",
                WorkOrderDetailUrlBuilder.build(
                        "https://example.test/base path", 123L, "tenant & one"));
    }

    @Test
    void returnsNullWhenBaseUrlIsBlank() {
        assertNull(WorkOrderDetailUrlBuilder.build("  ", 123L, "tenant-100"));
    }

    @Test
    void rejectsNullOrBlankTenantCode() {
        IllegalArgumentException nullCode = assertThrows(IllegalArgumentException.class,
                () -> WorkOrderDetailUrlBuilder.build("https://example.test", 123L, null));
        IllegalArgumentException blankCode = assertThrows(IllegalArgumentException.class,
                () -> WorkOrderDetailUrlBuilder.build("https://example.test", 123L, "  "));

        assertTrue(nullCode.getMessage().contains("tenantCode"));
        assertTrue(blankCode.getMessage().contains("tenantCode"));
    }
}
