package top.hetao.shiyuanticketmp.common.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 所有业务实体的基类。
 *
 * <p>提供以下公共字段：
 * <ul>
 *   <li>{@code id} — 雪花算法主键，应用层生成，不依赖数据库自增</li>
 *   <li>{@code tenantId} — 租户 ID，由 MyBatis-Plus TenantLineInnerInterceptor 自动注入</li>
 *   <li>{@code version} — 乐观锁版本号，由 MyBatis-Plus OptimisticLockerInnerInterceptor 自动管理</li>
 *   <li>{@code createdAt} / {@code updatedAt} — 自动填充的时间戳</li>
 *   <li>{@code deleted} — 逻辑删除标记</li>
 * </ul>
 */
@Data
public abstract class BaseEntity implements Serializable {

    /** 雪花算法主键 */
    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    /** 租户 ID（多租户物理隔离，由拦截器自动拼接到 SQL） */
    @TableField("tenant_id")
    private Long tenantId;

    /** 乐观锁版本号（更新时自动 +1，防止并发覆写） */
    @Version
    @TableField("version")
    private Integer version;

    /** 创建时间（插入时自动填充） */
    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;

    /** 更新时间（插入和更新时自动填充） */
    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updatedAt;

    /** 逻辑删除标记（0=未删除 1=已删除） */
    @TableLogic
    @TableField("deleted")
    private Integer deleted;
}
