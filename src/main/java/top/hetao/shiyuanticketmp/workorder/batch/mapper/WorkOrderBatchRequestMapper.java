package top.hetao.shiyuanticketmp.workorder.batch.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;
import top.hetao.shiyuanticketmp.workorder.batch.entity.WorkOrderBatchRequest;

@Mapper
public interface WorkOrderBatchRequestMapper extends BaseMapper<WorkOrderBatchRequest> {

    @Insert("""
            INSERT IGNORE INTO work_order_batch_request
                (id, tenant_id, requester_id, idempotency_key, request_hash,
                 work_order_ids_json, created_at, updated_at, deleted)
            VALUES
                (#{id}, #{tenantId}, #{requesterId}, #{key}, #{requestHash},
                 NULL, NOW(), NOW(), 0)
            """)
    int insertClaim(@Param("id") Long id,
                    @Param("tenantId") Long tenantId,
                    @Param("requesterId") Long requesterId,
                    @Param("key") String key,
                    @Param("requestHash") String requestHash);

    @Select("""
            SELECT * FROM work_order_batch_request
            WHERE tenant_id = #{tenantId}
              AND requester_id = #{requesterId}
              AND idempotency_key = #{key}
              AND deleted = 0
            FOR UPDATE
            """)
    WorkOrderBatchRequest selectClaimForUpdate(@Param("tenantId") Long tenantId,
                                                @Param("requesterId") Long requesterId,
                                                @Param("key") String key);

    @Update("""
            UPDATE work_order_batch_request
            SET work_order_ids_json = #{idsJson}, updated_at = NOW()
            WHERE id = #{id} AND work_order_ids_json IS NULL AND deleted = 0
            """)
    int completeClaim(@Param("id") Long id, @Param("idsJson") String idsJson);
}
