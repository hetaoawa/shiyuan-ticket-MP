package top.hetao.shiyuanticketmp.workorder.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;
import top.hetao.shiyuanticketmp.workorder.entity.WorkOrder;
import top.hetao.shiyuanticketmp.workorder.enums.WorkOrderType;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 工单 Mapper，继承 MyBatis-Plus BaseMapper 获得通用 CRUD 能力。
 *
 * <p>BaseMapper 提供的方法包括：insert、deleteById、updateById、selectById、selectList 等。
 * 复杂查询可使用 MyBatis-Plus 的 Wrapper 条件构造器，或保留自定义注解 SQL。
 *
 * <p>普通 CRUD 使用 BaseMapper 通用方法；跨租户候选发现和并发派发
 * 使用显式注解 SQL，以便把完整的原子条件固定在单条数据库语句中。
 */
@Mapper
public interface WorkOrderMapper extends BaseMapper<WorkOrder> {

    /**
     * Cross-tenant infrastructure query. Callers must hold
     * {@link top.hetao.shiyuanticketmp.common.context.TenantContext#useInternalBypass()}
     * for this single discovery operation.
     */
    @Select("""
            SELECT wo.*
            FROM work_order wo
            INNER JOIN sys_tenant t
                    ON t.id = wo.tenant_id
                   AND t.status = 1
                   AND t.deleted = 0
            WHERE wo.created_via_webhook = 1
              AND wo.status = 'PENDING'
              AND wo.assignee_id IS NULL
              AND wo.assignee_role IS NULL
              AND wo.assigned_at IS NULL
              AND wo.deleted = 0
              AND wo.created_at <= #{cutoff}
              AND EXISTS (
                    SELECT 1
                    FROM sys_role r
                    WHERE r.tenant_id = wo.tenant_id
                      AND r.role_code = 'WAREHOUSE_ADMIN'
                      AND r.deleted = 0
              )
            ORDER BY wo.created_at ASC, wo.id ASC
            LIMIT #{limit}
            """)
    List<WorkOrder> selectExternalAutoAssignmentCandidates(
            @Param("cutoff") LocalDateTime cutoff,
            @Param("limit") int limit);

    @Update("""
            UPDATE work_order
            SET assignee_id = #{assigneeId},
                assignee_role = NULL,
                status = 'IN_PROGRESS',
                assigned_at = #{assignedAt},
                updated_at = #{assignedAt}
            WHERE id = #{workOrderId}
              AND status = 'PENDING'
              AND assignee_id IS NULL
              AND assignee_role IS NULL
              AND assigned_at IS NULL
              AND deleted = 0
            """)
    int assignPendingToUser(
            @Param("workOrderId") Long workOrderId,
            @Param("assigneeId") Long assigneeId,
            @Param("assignedAt") LocalDateTime assignedAt);

    @Update("""
            UPDATE work_order
            SET assignee_id = NULL,
                assignee_role = #{roleCode},
                status = 'IN_PROGRESS',
                assigned_at = #{assignedAt},
                updated_at = #{assignedAt}
            WHERE id = #{workOrderId}
              AND status = 'PENDING'
              AND assignee_id IS NULL
              AND assignee_role IS NULL
              AND assigned_at IS NULL
              AND deleted = 0
            """)
    int assignPendingToRole(
            @Param("workOrderId") Long workOrderId,
            @Param("roleCode") String roleCode,
            @Param("assignedAt") LocalDateTime assignedAt);

    /**
     * Atomically claims an untouched external inbound order for the tenant's
     * warehouse-admin role. A zero result is a benign lost race.
     */
    @Update("""
            UPDATE work_order
            SET assignee_role = 'WAREHOUSE_ADMIN',
                status = 'IN_PROGRESS',
                assigned_at = #{assignedAt},
                updated_at = #{assignedAt}
            WHERE id = #{workOrderId}
              AND tenant_id = #{tenantId}
              AND created_via_webhook = 1
              AND status = 'PENDING'
              AND assignee_id IS NULL
              AND assignee_role IS NULL
              AND assigned_at IS NULL
              AND deleted = 0
              AND EXISTS (
                    SELECT 1
                    FROM sys_tenant t
                    WHERE t.id = work_order.tenant_id
                      AND t.status = 1
                      AND t.deleted = 0
              )
              AND EXISTS (
                    SELECT 1
                    FROM sys_role r
                    WHERE r.tenant_id = work_order.tenant_id
                      AND r.role_code = 'WAREHOUSE_ADMIN'
                      AND r.deleted = 0
              )
            """)
    int claimExternalInboundForWarehouseRole(
            @Param("workOrderId") Long workOrderId,
            @Param("tenantId") Long tenantId,
            @Param("assignedAt") LocalDateTime assignedAt);

    @Update("""
            UPDATE work_order
            SET status = 'CLOSED', resolution = #{resolution}, closed_at = #{closedAt},
                updated_at = #{closedAt}
            WHERE id = #{workOrderId} AND status = 'IN_PROGRESS' AND deleted = 0
            """)
    int closeInProgress(@Param("workOrderId") Long workOrderId,
                        @Param("resolution") String resolution,
                        @Param("closedAt") LocalDateTime closedAt);

    @Update("""
            UPDATE work_order
            SET status = 'REJECTED', rejection_reason = #{reason}, closed_at = #{closedAt},
                assignee_id = NULL, assignee_role = NULL, assigned_at = NULL,
                updated_at = #{closedAt}
            WHERE id = #{workOrderId} AND status = 'IN_PROGRESS' AND deleted = 0
            """)
    int rejectInProgress(@Param("workOrderId") Long workOrderId,
                         @Param("reason") String reason,
                         @Param("closedAt") LocalDateTime closedAt);

    @Update("""
            UPDATE work_order
            SET status = 'PENDING', rejection_reason = NULL, closed_at = NULL,
                assignee_id = NULL, assignee_role = NULL, assigned_at = NULL,
                title = #{title}, description = #{description}, tracking_no = #{trackingNo},
                target_address = #{targetAddress}, priority = #{priority}, type = #{type},
                updated_at = #{updatedAt}
            WHERE id = #{workOrderId} AND status = 'REJECTED' AND deleted = 0
            """)
    int resubmitRejected(@Param("workOrderId") Long workOrderId,
                         @Param("title") String title,
                         @Param("description") String description,
                         @Param("trackingNo") String trackingNo,
                         @Param("targetAddress") String targetAddress,
                         @Param("priority") Integer priority,
                         @Param("type") WorkOrderType type,
                         @Param("updatedAt") LocalDateTime updatedAt);

    @Update("""
            UPDATE work_order
            SET status = 'REJECTED', rejection_reason = #{reason}, closed_at = #{closedAt},
                assignee_id = NULL, assignee_role = NULL, assigned_at = NULL,
                updated_at = #{closedAt}
            WHERE id = #{workOrderId} AND status IN ('PENDING', 'IN_PROGRESS') AND deleted = 0
            """)
    int forceRejectActive(@Param("workOrderId") Long workOrderId,
                          @Param("reason") String reason,
                          @Param("closedAt") LocalDateTime closedAt);
}
