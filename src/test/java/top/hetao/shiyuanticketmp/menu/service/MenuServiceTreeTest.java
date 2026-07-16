package top.hetao.shiyuanticketmp.menu.service;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import org.junit.jupiter.api.Test;
import top.hetao.shiyuanticketmp.menu.entity.SysMenu;
import top.hetao.shiyuanticketmp.tenant.service.TenantLifecycleGuard;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;

class MenuServiceTreeTest {

    @Test
    void navigationTreePrunesEmptyDirectoriesAndButtonsPostOrder() {
        MenuService service = serviceWithMenus(List.of(
                menu(1L, 0L, "allowed-dir", "DIR", null, 1),
                menu(2L, 1L, "allowed-menu", "MENU", "menu:allowed", 1),
                menu(3L, 1L, "denied-menu", "MENU", "menu:denied", 1),
                menu(4L, 2L, "visible-button", "BUTTON", "button:allowed", 1),
                menu(5L, 0L, "empty-dir", "DIR", null, 1),
                menu(6L, 5L, "only-denied-menu", "MENU", "menu:denied", 1)
        ));

        List<Map<String, Object>> tree = service.getMenuTree(
                List.of("menu:allowed", "button:allowed"));

        assertEquals(1, tree.size());
        assertEquals("allowed-dir", tree.get(0).get("menuCode"));
        List<?> children = (List<?>) tree.get(0).get("children");
        assertEquals(1, children.size());
        Map<?, ?> leaf = (Map<?, ?>) children.get(0);
        assertEquals("allowed-menu", leaf.get("menuCode"));
        assertEquals(List.of(), leaf.get("children"));
    }

    @Test
    void fullTreeKeepsButtonsAndAlwaysEmitsChildrenArrays() {
        MenuService service = serviceWithMenus(List.of(
                menu(1L, 0L, "root", "DIR", null, 1),
                menu(2L, 1L, "button", "BUTTON", "button:edit", 0)
        ));

        List<Map<String, Object>> tree = service.getFullTree();

        List<?> children = (List<?>) tree.get(0).get("children");
        assertEquals(1, children.size());
        Map<?, ?> button = (Map<?, ?>) children.get(0);
        assertEquals("button", button.get("menuCode"));
        assertTrue(button.containsKey("children"));
        assertEquals(List.of(), button.get("children"));
    }

    @SuppressWarnings("unchecked")
    private static MenuService serviceWithMenus(List<SysMenu> menus) {
        MenuService service = spy(new MenuService(mock(TenantLifecycleGuard.class)));
        doReturn(menus).when(service).list(any(Wrapper.class));
        return service;
    }

    private static SysMenu menu(Long id, Long parentId, String code, String type,
                                String permission, int visible) {
        SysMenu menu = new SysMenu();
        menu.setId(id);
        menu.setParentId(parentId);
        menu.setMenuCode(code);
        menu.setMenuName(code);
        menu.setMenuType(type);
        menu.setPermissionCode(permission);
        menu.setVisible(visible);
        return menu;
    }
}
