package top.hetao.shiyuanticketmp.tenant.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

/** 权威租户主数据；该表为全局表，不继承带 tenant_id 的 BaseEntity。 */
@Data
@TableName("sys_tenant")
public class SysTenant implements Serializable {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    private String tenantCode;

    private String tenantName;

    private Integer status;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updatedAt;

    @TableLogic
    private Integer deleted;
}
