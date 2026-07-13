package db.migration;

import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;
import top.hetao.shiyuanticketmp.menu.template.CanonicalMenuTemplate;
import top.hetao.shiyuanticketmp.menu.template.CanonicalMenuTemplate.Definition;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/** Repairs tenant 0 as the canonical menu template and provisions every business tenant. */
public class V22__repair_and_provision_tenant_menus extends BaseJavaMigration {

    private static final int MIGRATION_ALGORITHM_VERSION = 2;

    @Override
    public Integer getChecksum() {
        return CanonicalMenuTemplate.checksum(MIGRATION_ALGORITHM_VERSION);
    }

    @Override
    public void migrate(Context context) throws Exception {
        Connection connection = context.getConnection();
        provision(connection, 0L);
        List<Long> businessTenantIds = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT id FROM sys_tenant WHERE id > 0 AND deleted = 0 ORDER BY id");
             ResultSet rows = statement.executeQuery()) {
            while (rows.next()) {
                businessTenantIds.add(rows.getLong(1));
            }
        }
        for (Long tenantId : businessTenantIds) {
            provision(connection, tenantId);
        }
    }

    private void provision(Connection connection, long tenantId) throws SQLException {
        Map<String, Long> existingIds = loadExistingIds(connection, tenantId);
        Map<String, Long> provisionedIds = new HashMap<>();
        for (Definition template : CanonicalMenuTemplate.definitions()) {
            long parentId = template.parentCode() == null
                    ? 0L : requireParentId(provisionedIds, template);
            Long existingId = existingIds.get(template.code());
            long menuId = existingId == null ? nextUnusedId(connection) : existingId;
            if (existingId == null) {
                insert(connection, tenantId, menuId, parentId, template);
            } else {
                update(connection, tenantId, menuId, parentId, template);
            }
            provisionedIds.put(template.code(), menuId);
        }
    }

    private Map<String, Long> loadExistingIds(Connection connection, long tenantId) throws SQLException {
        Set<String> canonicalCodes = CanonicalMenuTemplate.definitions().stream()
                .map(Definition::code)
                .collect(Collectors.toUnmodifiableSet());
        Map<String, Long> ids = new LinkedHashMap<>();
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT id, menu_code FROM sys_menu WHERE tenant_id = ? ORDER BY id")) {
            statement.setLong(1, tenantId);
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    String code = rows.getString("menu_code");
                    if (!canonicalCodes.contains(code)) {
                        continue;
                    }
                    if (ids.put(code, rows.getLong("id")) != null) {
                        throw new SQLException("Duplicate menu code for tenant " + tenantId + ": " + code);
                    }
                }
            }
        }
        return ids;
    }

    private long nextUnusedId(Connection connection) throws SQLException {
        while (true) {
            long candidate = IdWorker.getId();
            try (PreparedStatement statement = connection.prepareStatement(
                    "SELECT 1 FROM sys_menu WHERE id = ?")) {
                statement.setLong(1, candidate);
                try (ResultSet row = statement.executeQuery()) {
                    if (!row.next()) {
                        return candidate;
                    }
                }
            }
        }
    }

    private void insert(Connection connection, long tenantId, long menuId, long parentId,
                        Definition template) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO sys_menu
                    (id, tenant_id, parent_id, menu_name, menu_code, path, icon, sort_order,
                     menu_type, permission_code, visible, version, created_at, updated_at, deleted)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 1, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 0)
                """)) {
            bind(statement, tenantId, menuId, parentId, template);
            statement.executeUpdate();
        }
    }

    private void update(Connection connection, long tenantId, long menuId, long parentId,
                        Definition template) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                UPDATE sys_menu
                SET parent_id = ?, path = ?, menu_type = ?, permission_code = ?,
                    updated_at = CURRENT_TIMESTAMP,
                    deleted = 0
                WHERE id = ? AND tenant_id = ? AND menu_code = ?
                """)) {
            statement.setLong(1, parentId);
            statement.setString(2, template.path());
            statement.setString(3, template.type());
            statement.setString(4, template.permissionCode());
            statement.setLong(5, menuId);
            statement.setLong(6, tenantId);
            statement.setString(7, template.code());
            if (statement.executeUpdate() != 1) {
                throw new SQLException("Failed to update canonical menu " + template.code()
                        + " for tenant " + tenantId);
            }
        }
    }

    private void bind(PreparedStatement statement, long tenantId, long menuId, long parentId,
                      Definition template) throws SQLException {
        statement.setLong(1, menuId);
        statement.setLong(2, tenantId);
        statement.setLong(3, parentId);
        statement.setString(4, template.name());
        statement.setString(5, template.code());
        statement.setString(6, template.path());
        statement.setString(7, template.icon());
        statement.setInt(8, template.sortOrder());
        statement.setString(9, template.type());
        statement.setString(10, template.permissionCode());
        statement.setInt(11, template.visible());
    }

    private long requireParentId(Map<String, Long> provisionedIds, Definition template)
            throws SQLException {
        Long parentId = provisionedIds.get(template.parentCode());
        if (parentId == null) {
            throw new SQLException("Canonical menu parent must precede child: " + template.code());
        }
        return parentId;
    }

}
