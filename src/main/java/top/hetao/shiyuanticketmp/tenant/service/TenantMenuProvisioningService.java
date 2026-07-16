package top.hetao.shiyuanticketmp.tenant.service;

import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import top.hetao.shiyuanticketmp.common.context.TenantContext;
import top.hetao.shiyuanticketmp.menu.entity.SysMenu;
import top.hetao.shiyuanticketmp.menu.mapper.SysMenuMapper;
import top.hetao.shiyuanticketmp.menu.template.CanonicalMenuTemplate;
import top.hetao.shiyuanticketmp.menu.template.CanonicalMenuTemplate.Definition;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/** Provisions the shared immutable canonical menu tree into one business tenant. */
@Service
public class TenantMenuProvisioningService {

    private final SysMenuMapper menuMapper;
    private final TenantLifecycleGuard tenantLifecycleGuard;

    public TenantMenuProvisioningService(SysMenuMapper menuMapper,
                                         TenantLifecycleGuard tenantLifecycleGuard) {
        this.menuMapper = menuMapper;
        this.tenantLifecycleGuard = tenantLifecycleGuard;
    }

    /**
     * Repeat-safe provisioning preserves existing IDs and presentation customizations while
     * repairing routing/security fields owned by the canonical definition.
     */
    @Transactional
    public void provisionTenantMenus(Long tenantId) {
        if (tenantId == null || tenantId <= 0) {
            throw new IllegalArgumentException("Business tenant id must be positive");
        }
        // Safe for new tenants: this transaction already owns the inserted tenant row lock.
        tenantLifecycleGuard.lockWritableTenant(tenantId);
        try (TenantContext.Scope ignored = TenantContext.useInternalBypass()) {
            provision(tenantId, menuMapper.selectAllByTenantIncludingDeleted(tenantId));
        }
    }

    private void provision(Long tenantId, List<SysMenu> existingMenus) {
        List<Definition> definitions = CanonicalMenuTemplate.definitions();
        Set<String> canonicalCodes = definitions.stream()
                .map(Definition::code)
                .collect(Collectors.toUnmodifiableSet());
        Map<String, SysMenu> targetByCode = new HashMap<>();
        for (SysMenu existing : existingMenus) {
            if (existing.getMenuCode() == null || !canonicalCodes.contains(existing.getMenuCode())) {
                continue;
            }
            if (targetByCode.put(existing.getMenuCode(), existing) != null) {
                throw new IllegalStateException("Duplicate tenant menu code: " + existing.getMenuCode());
            }
        }

        for (Definition definition : definitions) {
            Long parentId = resolveTargetParentId(definition, targetByCode);
            SysMenu target = targetByCode.get(definition.code());
            boolean insert = target == null;
            if (insert) {
                target = new SysMenu();
                target.setId(IdWorker.getId());
                target.setTenantId(tenantId);
            }
            applyCanonicalFields(definition, target, parentId, insert);
            if (insert) {
                menuMapper.insertProvisionedMenu(target);
            } else {
                target.setTenantId(tenantId);
                menuMapper.updateProvisionedMenu(target);
            }
            targetByCode.put(definition.code(), target);
        }
    }

    private static Long resolveTargetParentId(Definition definition,
                                              Map<String, SysMenu> targetByCode) {
        if (definition.parentCode() == null) {
            return 0L;
        }
        SysMenu targetParent = targetByCode.get(definition.parentCode());
        if (targetParent == null || targetParent.getId() == null) {
            throw new IllegalStateException("Canonical menu parent was not provisioned: "
                    + definition.code());
        }
        return targetParent.getId();
    }

    private static void applyCanonicalFields(Definition definition, SysMenu target, Long parentId,
                                             boolean insert) {
        target.setParentId(parentId);
        target.setPath(definition.path());
        target.setMenuType(definition.type());
        target.setPermissionCode(definition.permissionCode());
        target.setDeleted(0);
        if (insert) {
            target.setMenuName(definition.name());
            target.setMenuCode(definition.code());
            target.setIcon(definition.icon());
            target.setSortOrder(definition.sortOrder());
            target.setVisible(definition.visible());
        }
    }
}
