package top.hetao.shiyuanticketmp.auth.mapper;

import com.baomidou.mybatisplus.annotation.InterceptorIgnore;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import top.hetao.shiyuanticketmp.auth.entity.SysUser;

/**
 * 用户 Mapper
 */
@Mapper
public interface SysUserMapper extends BaseMapper<SysUser> {

    /**
     * 根据用户名查询用户（忽略租户过滤，用于登录）。
     *
     * @param username 用户名
     * @return 用户实体，不存在时返回 null
     */
    @InterceptorIgnore(tenantLine = "true")
    @Select("SELECT * FROM sys_user WHERE username = #{username} AND deleted = 0")
    SysUser selectByUsernameIgnoreTenant(@Param("username") String username);

    /**
     * 根据 ID 查询用户（忽略租户过滤，用于已认证用户的 /me 接口）。
     *
     * @param id 用户 ID
     * @return 用户实体，不存在时返回 null
     */
    @InterceptorIgnore(tenantLine = "true")
    @Select("SELECT * FROM sys_user WHERE id = #{id} AND deleted = 0")
    SysUser selectByIdIgnoreTenant(@Param("id") Long id);

    /**
     * 根据外部用户 ID 查询用户（忽略租户过滤，用于 webhook 外部入站）。
     *
     * @param externalUserId 外部系统用户 ID
     * @return 用户实体，不存在时返回 null
     */
    @InterceptorIgnore(tenantLine = "true")
    @Select("SELECT * FROM sys_user WHERE external_user_id = #{externalUserId} AND deleted = 0")
    SysUser selectByExternalUserIdIgnoreTenant(@Param("externalUserId") String externalUserId);
}
