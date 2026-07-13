package top.hetao.shiyuanticketmp.menu.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;
import top.hetao.shiyuanticketmp.menu.entity.SysMenu;

import java.util.List;

/**
 * 菜单 Mapper。
 */
@Mapper
public interface SysMenuMapper extends BaseMapper<SysMenu> {

    /** Infrastructure-only read that also sees logically deleted rows blocked by uk_code. */
    @Select("""
            SELECT id, tenant_id, parent_id, menu_name, menu_code, path, icon,
                   sort_order, menu_type, permission_code, visible,
                   created_at, updated_at, deleted
            FROM sys_menu
            WHERE tenant_id = #{tenantId}
            ORDER BY sort_order, id
            """)
    List<SysMenu> selectAllByTenantIncludingDeleted(@Param("tenantId") Long tenantId);

    @Insert("""
            INSERT INTO sys_menu
                (id, tenant_id, parent_id, menu_name, menu_code, path, icon,
                 sort_order, menu_type, permission_code, visible, version,
                 created_at, updated_at, deleted)
            VALUES
                (#{id}, #{tenantId}, #{parentId}, #{menuName}, #{menuCode}, #{path}, #{icon},
                 #{sortOrder}, #{menuType}, #{permissionCode}, #{visible}, 1,
                 CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 0)
            """)
    int insertProvisionedMenu(SysMenu menu);

    @Update("""
            UPDATE sys_menu
            SET parent_id = #{parentId}, menu_name = #{menuName}, path = #{path},
                icon = #{icon}, sort_order = #{sortOrder}, menu_type = #{menuType},
                permission_code = #{permissionCode}, visible = #{visible},
                updated_at = CURRENT_TIMESTAMP, deleted = 0
            WHERE id = #{id} AND tenant_id = #{tenantId} AND menu_code = #{menuCode}
            """)
    int updateProvisionedMenu(SysMenu menu);
}
