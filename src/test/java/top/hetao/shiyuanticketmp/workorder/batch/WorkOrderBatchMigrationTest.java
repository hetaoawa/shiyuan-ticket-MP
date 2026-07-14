package top.hetao.shiyuanticketmp.workorder.batch;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

class WorkOrderBatchMigrationTest {
    @Test
    void migrationDefinesTenantRequesterKeyUniquenessAndOrderedIdsStorage() throws Exception {
        String sql;
        try (var input = getClass().getResourceAsStream(
                "/db/migration/V24__create_work_order_batch_request.sql")) {
            assertThat(input).isNotNull();
            sql = new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
        assertThat(sql).contains("CREATE TABLE work_order_batch_request")
                .contains("request_hash")
                .contains("work_order_ids_json")
                .contains("UNIQUE KEY uk_work_order_batch_idempotency (tenant_id, requester_id, idempotency_key)");
    }
}
