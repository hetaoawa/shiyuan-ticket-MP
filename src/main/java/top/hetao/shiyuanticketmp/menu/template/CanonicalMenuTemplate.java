package top.hetao.shiyuanticketmp.menu.template;

import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.zip.CRC32;

/** Immutable canonical menu definitions shared by Flyway and runtime provisioning. */
public final class CanonicalMenuTemplate {

    private static final List<Definition> DEFINITIONS = List.of(
            new Definition("workorder", null, "工单管理", "/workorder", "file-text", 1, "DIR", null, 1),
            new Definition("system", null, "系统管理", "/system", "setting", 99, "DIR", null, 1),
            new Definition("workorder:list", "workorder", "工单列表", "/workorder/list", "list", 1, "MENU", "workorder:view", 1),
            new Definition("workorder:create", "workorder", "创建工单", "/workorder/create", "plus", 2, "MENU", "workorder:create", 1),
            new Definition("system:user", "system", "用户管理", "/system/user", "user", 1, "MENU", "user:view", 1),
            new Definition("system:role", "system", "角色管理", "/system/role", "team", 2, "MENU", "role:view", 1),
            new Definition("system:menu", "system", "菜单管理", "/system/menu", "menu", 3, "MENU", "menu:view", 1),
            new Definition("system:deadletter", "system", "死信管理", "/system/deadletter", "warning", 4, "MENU", "deadletter:view", 1),
            new Definition("system:audit", "system", "审计日志", "/system/audit", "file-search", 5, "MENU", "audit:view", 1),
            new Definition("system:user:create", "system:user", "新增用户", null, null, 1, "BUTTON", "user:create", 0),
            new Definition("system:user:update", "system:user", "编辑用户", null, null, 2, "BUTTON", "user:update", 0),
            new Definition("system:user:delete", "system:user", "删除用户", null, null, 3, "BUTTON", "user:delete", 0),
            new Definition("system:role:create", "system:role", "新增角色", null, null, 1, "BUTTON", "role:create", 0),
            new Definition("system:role:update", "system:role", "编辑角色", null, null, 2, "BUTTON", "role:update", 0),
            new Definition("system:role:delete", "system:role", "删除角色", null, null, 3, "BUTTON", "role:delete", 0),
            new Definition("system:menu:create", "system:menu", "新增菜单", null, null, 1, "BUTTON", "menu:create", 0),
            new Definition("system:menu:update", "system:menu", "编辑菜单", null, null, 2, "BUTTON", "menu:update", 0),
            new Definition("system:menu:delete", "system:menu", "删除菜单", null, null, 3, "BUTTON", "menu:delete", 0),
            new Definition("workorder:comment", "workorder:list", "工单评论", null, null, 3, "BUTTON", "workorder:comment", 0),
            new Definition("workorder:export", "workorder:list", "工单导出", null, null, 4, "BUTTON", "workorder:export", 0)
    );

    static {
        Set<String> precedingCodes = new HashSet<>();
        for (Definition definition : DEFINITIONS) {
            if (definition.code() == null || definition.code().isBlank()
                    || !precedingCodes.add(definition.code())) {
                throw new IllegalStateException("Duplicate/blank canonical menu code: " + definition.code());
            }
            if (definition.parentCode() != null && !precedingCodes.contains(definition.parentCode())) {
                throw new IllegalStateException("Canonical parent must precede child: " + definition.code());
            }
        }
    }

    private CanonicalMenuTemplate() {
    }

    public static List<Definition> definitions() {
        return DEFINITIONS;
    }

    /** Stable checksum input includes both template fields and caller-owned algorithm version. */
    public static int checksum(int algorithmVersion) {
        CRC32 crc = new CRC32();
        update(crc, Integer.toString(algorithmVersion));
        for (Definition definition : DEFINITIONS) {
            update(crc, definition.code());
            update(crc, definition.parentCode());
            update(crc, definition.name());
            update(crc, definition.path());
            update(crc, definition.icon());
            update(crc, Integer.toString(definition.sortOrder()));
            update(crc, definition.type());
            update(crc, definition.permissionCode());
            update(crc, Integer.toString(definition.visible()));
        }
        return (int) crc.getValue();
    }

    private static void update(CRC32 crc, String value) {
        byte[] bytes = (value == null ? "<null>" : value).getBytes(StandardCharsets.UTF_8);
        crc.update(bytes, 0, bytes.length);
        crc.update(0);
    }

    public record Definition(String code, String parentCode, String name, String path,
                             String icon, int sortOrder, String type,
                             String permissionCode, int visible) {
    }
}
