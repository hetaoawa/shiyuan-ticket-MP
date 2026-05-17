package top.hetao.shiyuanticketmp.auth.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;
import lombok.EqualsAndHashCode;
import top.hetao.shiyuanticketmp.common.entity.BaseEntity;

/**
 * 用户实体，对应数据库表 {@code sys_user}。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("sys_user")
public class SysUser extends BaseEntity {

    /** 用户名 */
    private String username;

    /** 密码（BCrypt 加密） */
    @JsonProperty(access = JsonProperty.Access.WRITE_ONLY)
    private String password;

    /** 昵称 */
    private String nickname;

    /** 手机号 */
    private String phone;

    /** 邮箱 */
    private String email;

    /** 状态 1=启用 0=禁用 */
    private Integer status;

    /** 外部系统用户 ID（用于外部 WebHook 发起工单时关联系统用户） */
    private String externalUserId;
}
