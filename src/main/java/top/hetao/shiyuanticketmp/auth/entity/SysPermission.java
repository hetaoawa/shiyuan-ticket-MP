package top.hetao.shiyuanticketmp.auth.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import java.time.LocalDateTime;

/**
 * 权限实体，对应数据库表 {@code sys_permission}。
 *
 * <p>权限表无租户隔离（全局共享），因此不继承 BaseEntity。
 */
@Data
@TableName("sys_permission")
public class SysPermission {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    /** 权限编码 */
    private String permissionCode;

    /** 权限名称 */
    private String permissionName;

    private LocalDateTime createdAt;
}
