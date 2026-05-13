package top.hetao.shiyuanticketmp.menu.controller.dto;

import lombok.Data;

@Data
public class CreateMenuRequest {
    private Long parentId;
    private String menuName;
    private String menuCode;
    private String path;
    private String icon;
    private Integer sortOrder;
    private String menuType;
    private String permissionCode;
    private Integer visible;
}
