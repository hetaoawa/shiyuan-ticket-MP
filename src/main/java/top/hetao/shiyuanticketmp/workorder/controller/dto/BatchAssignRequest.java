package top.hetao.shiyuanticketmp.workorder.controller.dto;

import lombok.Data;

import java.util.List;

/**
 * 批量派发请求体。
 *
 * <p>支持按用户或按角色派发，二者必选其一且互斥：
 * <ul>
 *   <li>{@code assigneeId} — 按用户派发（清除 assigneeRole）</li>
 *   <li>{@code assigneeRoleCode} — 按角色派发（清除 assigneeId），如 WAREHOUSE_ADMIN</li>
 * </ul>
 */
@Data
public class BatchAssignRequest {

    /** 工单ID列表（必须都是 PENDING 状态） */
    private List<Long> workOrderIds;

    /** 处理人ID（按用户派发时必填，与 assigneeRoleCode 互斥） */
    private Long assigneeId;

    /** 角色编码（按角色派发时必填，与 assigneeId 互斥），如 WAREHOUSE_ADMIN */
    private String assigneeRoleCode;
}
