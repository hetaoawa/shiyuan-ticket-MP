package top.hetao.shiyuanticketmp.auth.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;
import top.hetao.shiyuanticketmp.auth.entity.SysPermission;

import java.util.List;

/**
 * 权限 Mapper
 */
@Mapper
public interface SysPermissionMapper extends BaseMapper<SysPermission> {

    /**
     * 根据用户 ID 查询其所有权限编码（通过角色关联）
     */
    @Select("SELECT DISTINCT p.permission_code FROM sys_permission p " +
            "INNER JOIN sys_role_permission rp ON rp.permission_id = p.id " +
            "INNER JOIN sys_role r ON r.id = rp.role_id " +
            "INNER JOIN sys_user_role ur ON ur.role_id = r.id " +
            "INNER JOIN sys_user u ON u.id = ur.user_id " +
            "WHERE u.id = #{userId} AND u.deleted = 0 AND r.deleted = 0 " +
            "AND r.tenant_id = u.tenant_id")
    List<String> selectPermissionCodesByUserId(Long userId);
}
