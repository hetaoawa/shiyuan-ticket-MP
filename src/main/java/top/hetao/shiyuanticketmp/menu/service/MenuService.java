package top.hetao.shiyuanticketmp.menu.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import top.hetao.shiyuanticketmp.menu.controller.dto.CreateMenuRequest;
import top.hetao.shiyuanticketmp.menu.controller.dto.UpdateMenuRequest;
import top.hetao.shiyuanticketmp.menu.entity.SysMenu;
import top.hetao.shiyuanticketmp.menu.mapper.SysMenuMapper;
import top.hetao.shiyuanticketmp.workorder.exception.WorkOrderException;

import java.util.*;
import java.util.stream.Collectors;

@Service
public class MenuService extends ServiceImpl<SysMenuMapper, SysMenu> {

    public List<Map<String, Object>> getMenuTree(List<String> userPermissions) {
        List<SysMenu> allMenus = list(new LambdaQueryWrapper<SysMenu>()
                .eq(SysMenu::getVisible, 1)
                .orderByAsc(SysMenu::getSortOrder));

        Set<String> permSet = new HashSet<>(userPermissions);
        List<SysMenu> accessibleMenus = allMenus.stream()
                .filter(menu -> menu.getPermissionCode() == null
                        || menu.getPermissionCode().isBlank()
                        || permSet.contains(menu.getPermissionCode()))
                .collect(Collectors.toList());

        return buildTree(accessibleMenus, 0L);
    }

    public List<Map<String, Object>> getFullTree() {
        List<SysMenu> allMenus = list(new LambdaQueryWrapper<SysMenu>()
                .orderByAsc(SysMenu::getSortOrder));
        return buildTree(allMenus, 0L);
    }

    @Transactional
    public SysMenu createMenu(CreateMenuRequest request) {
        if (request.getMenuCode() != null && !request.getMenuCode().isBlank()) {
            long count = count(new LambdaQueryWrapper<SysMenu>()
                    .eq(SysMenu::getMenuCode, request.getMenuCode()));
            if (count > 0) {
                throw new WorkOrderException("菜单编码已存在: " + request.getMenuCode());
            }
        }
        SysMenu menu = new SysMenu();
        menu.setParentId(request.getParentId() != null ? request.getParentId() : 0L);
        menu.setMenuName(request.getMenuName());
        menu.setMenuCode(request.getMenuCode());
        menu.setPath(request.getPath());
        menu.setIcon(request.getIcon());
        menu.setSortOrder(request.getSortOrder() != null ? request.getSortOrder() : 0);
        menu.setMenuType(request.getMenuType());
        menu.setPermissionCode(request.getPermissionCode());
        menu.setVisible(request.getVisible() != null ? request.getVisible() : 1);
        save(menu);
        return menu;
    }

    @Transactional
    public void updateMenu(Long menuId, UpdateMenuRequest request) {
        SysMenu menu = getById(menuId);
        if (menu == null) {
            throw new WorkOrderException("菜单不存在: " + menuId);
        }
        if (request.getParentId() != null) menu.setParentId(request.getParentId());
        if (request.getMenuName() != null) menu.setMenuName(request.getMenuName());
        if (request.getMenuCode() != null) menu.setMenuCode(request.getMenuCode());
        if (request.getPath() != null) menu.setPath(request.getPath());
        if (request.getIcon() != null) menu.setIcon(request.getIcon());
        if (request.getSortOrder() != null) menu.setSortOrder(request.getSortOrder());
        if (request.getMenuType() != null) menu.setMenuType(request.getMenuType());
        if (request.getPermissionCode() != null) menu.setPermissionCode(request.getPermissionCode());
        if (request.getVisible() != null) menu.setVisible(request.getVisible());
        updateById(menu);
    }

    @Transactional
    public void deleteMenu(Long menuId) {
        SysMenu menu = getById(menuId);
        if (menu == null) {
            throw new WorkOrderException("菜单不存在: " + menuId);
        }
        long childCount = count(new LambdaQueryWrapper<SysMenu>()
                .eq(SysMenu::getParentId, menuId));
        if (childCount > 0) {
            throw new WorkOrderException("存在子菜单，无法删除");
        }
        removeById(menuId);
    }

    private List<Map<String, Object>> buildTree(List<SysMenu> menus, Long parentId) {
        List<Map<String, Object>> tree = new ArrayList<>();
        for (SysMenu menu : menus) {
            if (Objects.equals(menu.getParentId(), parentId)) {
                Map<String, Object> node = new LinkedHashMap<>();
                node.put("id", menu.getId());
                node.put("parentId", menu.getParentId());
                node.put("menuName", menu.getMenuName());
                node.put("menuCode", menu.getMenuCode());
                node.put("path", menu.getPath());
                node.put("icon", menu.getIcon());
                node.put("sortOrder", menu.getSortOrder());
                node.put("menuType", menu.getMenuType());
                node.put("permissionCode", menu.getPermissionCode());
                node.put("visible", menu.getVisible());

                List<Map<String, Object>> children = buildTree(menus, menu.getId());
                if (!children.isEmpty()) {
                    node.put("children", children);
                }
                tree.add(node);
            }
        }
        return tree;
    }
}
