package top.hetao.shiyuanticketmp.menu.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;
import top.hetao.shiyuanticketmp.common.entity.BaseEntity;

/**
 * 菜单实体，对应数据库表 {@code sys_menu}。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("sys_menu")
public class SysMenu extends BaseEntity {

    /** 父菜单ID（0=顶级） */
    private Long parentId;

    /** 菜单名称 */
    private String menuName;

    /** 菜单编码（唯一标识） */
    private String menuCode;

    /** 路由路径 */
    private String path;

    /** 图标 */
    private String icon;

    /** 排序号 */
    private Integer sortOrder;

    /** 菜单类型（DIR/MENU/BUTTON） */
    @TableField("menu_type")
    private String menuType;

    /** 关联权限编码 */
    private String permissionCode;

    /** 是否可见 */
    private Integer visible;
}
