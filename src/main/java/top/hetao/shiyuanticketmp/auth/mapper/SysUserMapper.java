package top.hetao.shiyuanticketmp.auth.mapper;

import com.baomidou.mybatisplus.annotation.InterceptorIgnore;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;
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
    @Select("""
            SELECT u.* FROM sys_user u
            INNER JOIN sys_tenant t ON t.id = u.tenant_id
            WHERE t.tenant_code = #{tenantCode}
              AND t.status = 1 AND t.deleted = 0
              AND u.username = #{username} AND u.deleted = 0
            """)
    SysUser selectByTenantCodeAndUsername(@Param("tenantCode") String tenantCode,
                                          @Param("username") String username);

    @Select("""
            SELECT u.* FROM sys_user u
            INNER JOIN sys_user_role ur ON ur.user_id = u.id
            INNER JOIN sys_role r ON r.id = ur.role_id
            WHERE u.tenant_id = #{tenantId} AND u.deleted = 0
              AND r.tenant_id = #{tenantId} AND r.role_code = 'SYSTEM_ADMIN' AND r.deleted = 0
            ORDER BY u.username
            """)
    java.util.List<SysUser> selectTenantSystemAdmins(@Param("tenantId") Long tenantId);

    /**
     * 根据 ID 查询用户（忽略租户过滤，用于已认证用户的 /me 接口）。
     *
     * @param id 用户 ID
     * @return 用户实体，不存在时返回 null
     */
    @Select("SELECT * FROM sys_user WHERE id = #{id} AND deleted = 0")
    @InterceptorIgnore(tenantLine = "true", dataPermission = "false")
    SysUser selectByIdIgnoreTenant(@Param("id") Long id);

    @Update("""
            UPDATE sys_user
            SET password = #{password}, updated_at = CURRENT_TIMESTAMP
            WHERE id = #{id} AND deleted = 0
            """)
    int updatePrincipalPasswordIgnoreTenant(@Param("id") Long id,
                                             @Param("password") String password);

    @Update("""
            <script>
            UPDATE sys_user
            <set>
              <if test="nicknamePresent">nickname = #{nickname},</if>
              <if test="phonePresent">phone = #{phone},</if>
              <if test="emailPresent">email = #{email},</if>
              updated_at = CURRENT_TIMESTAMP
            </set>
            WHERE id = #{id} AND deleted = 0
            </script>
            """)
    int updatePrincipalProfileIgnoreTenant(@Param("id") Long id,
                                           @Param("nicknamePresent") boolean nicknamePresent,
                                           @Param("nickname") String nickname,
                                           @Param("phonePresent") boolean phonePresent,
                                           @Param("phone") String phone,
                                           @Param("emailPresent") boolean emailPresent,
                                           @Param("email") String email);

    /**
     * 根据外部用户 ID 查询用户（忽略租户过滤，用于 webhook 外部入站）。
     *
     * @param externalUserId 外部系统用户 ID
     * @return 用户实体，不存在时返回 null
     */
    @Select("SELECT * FROM sys_user WHERE external_user_id = #{externalUserId} AND deleted = 0")
    SysUser selectByExternalUserIdIgnoreTenant(@Param("externalUserId") String externalUserId);

    @Select("""
            SELECT DISTINCT u.*
            FROM sys_user u
            JOIN sys_user_role ur ON ur.user_id = u.id
            JOIN sys_role r ON r.id = ur.role_id
            WHERE u.status = 1 AND u.deleted = 0
              AND r.role_code = #{roleCode} AND r.deleted = 0
            ORDER BY u.username
            """)
    java.util.List<SysUser> selectActiveUsersByRoleCode(@Param("roleCode") String roleCode);

    /**
     * Checks whether the given tenant has at least one enabled user bound to the role.
     *
     * <p>{@code sys_user_role} is a global relation table, so both sides of the
     * relation are constrained to the same tenant explicitly.</p>
     */
    @Select("""
            SELECT EXISTS(
                SELECT 1
                FROM sys_user u
                JOIN sys_user_role ur ON ur.user_id = u.id
                JOIN sys_role r ON r.id = ur.role_id
                WHERE u.tenant_id = #{tenantId}
                  AND r.tenant_id = #{tenantId}
                  AND r.role_code = #{roleCode}
                  AND u.status = 1
                  AND u.deleted = 0
                  AND r.deleted = 0
            )
            """)
    boolean existsActiveUserByTenantAndRoleCode(@Param("tenantId") Long tenantId,
                                                 @Param("roleCode") String roleCode);
}
