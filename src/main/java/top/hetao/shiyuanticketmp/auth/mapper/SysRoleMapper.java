package top.hetao.shiyuanticketmp.auth.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;
import top.hetao.shiyuanticketmp.auth.entity.SysRole;

import java.util.List;

/**
 * 角色 Mapper
 */
@Mapper
public interface SysRoleMapper extends BaseMapper<SysRole> {

    /**
     * 根据用户 ID 查询其所有角色编码
     */
    @Select("SELECT r.role_code FROM sys_role r " +
            "INNER JOIN sys_user_role ur ON ur.role_id = r.id " +
            "INNER JOIN sys_user u ON u.id = ur.user_id " +
            "WHERE u.id = #{userId} AND u.deleted = 0 AND r.deleted = 0 " +
            "AND r.tenant_id = u.tenant_id")
    List<String> selectRoleCodesByUserId(Long userId);
}
