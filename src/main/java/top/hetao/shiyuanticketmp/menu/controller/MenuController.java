package top.hetao.shiyuanticketmp.menu.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import cn.dev33.satoken.stp.StpUtil;
import org.springframework.web.bind.annotation.*;
import top.hetao.shiyuanticketmp.auth.service.UserService;
import top.hetao.shiyuanticketmp.menu.controller.dto.CreateMenuRequest;
import top.hetao.shiyuanticketmp.menu.controller.dto.UpdateMenuRequest;
import top.hetao.shiyuanticketmp.menu.entity.SysMenu;
import top.hetao.shiyuanticketmp.menu.service.MenuService;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/menus")
public class MenuController {

    private final MenuService menuService;
    private final UserService userService;

    public MenuController(MenuService menuService, UserService userService) {
        this.menuService = menuService;
        this.userService = userService;
    }

    @GetMapping
    public Map<String, Object> getMenuTree() {
        Long userId = StpUtil.getLoginIdAsLong();
        List<String> permissions = userService.getPermissionCodes(userId);
        List<Map<String, Object>> menuTree = menuService.getMenuTree(permissions);

        Map<String, Object> response = new HashMap<>();
        response.put("code", 200);
        response.put("data", menuTree);
        return response;
    }

    @SaCheckPermission("menu:view")
    @GetMapping("/admin/tree")
    public Map<String, Object> getFullTree() {
        Map<String, Object> response = new HashMap<>();
        response.put("code", 200);
        response.put("data", menuService.getFullTree());
        return response;
    }

    @SaCheckPermission("menu:create")
    @PostMapping("/admin")
    public Map<String, Object> create(@RequestBody CreateMenuRequest request) {
        SysMenu menu = menuService.createMenu(request);
        Map<String, Object> response = new HashMap<>();
        response.put("code", 200);
        response.put("message", "菜单创建成功");
        response.put("data", menu.getId());
        return response;
    }

    @SaCheckPermission("menu:update")
    @PutMapping("/admin/{id}")
    public Map<String, Object> update(@PathVariable Long id, @RequestBody UpdateMenuRequest request) {
        menuService.updateMenu(id, request);
        Map<String, Object> response = new HashMap<>();
        response.put("code", 200);
        response.put("message", "菜单更新成功");
        return response;
    }

    @SaCheckPermission("menu:delete")
    @DeleteMapping("/admin/{id}")
    public Map<String, Object> delete(@PathVariable Long id) {
        menuService.deleteMenu(id);
        Map<String, Object> response = new HashMap<>();
        response.put("code", 200);
        response.put("message", "菜单删除成功");
        return response;
    }
}
