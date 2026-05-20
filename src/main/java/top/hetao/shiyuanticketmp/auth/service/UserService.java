package top.hetao.shiyuanticketmp.auth.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import top.hetao.shiyuanticketmp.auth.controller.dto.CreateUserRequest;
import top.hetao.shiyuanticketmp.auth.controller.dto.UpdateUserRequest;
import top.hetao.shiyuanticketmp.auth.entity.SysUser;
import top.hetao.shiyuanticketmp.auth.entity.SysUserRole;
import top.hetao.shiyuanticketmp.auth.mapper.SysPermissionMapper;
import top.hetao.shiyuanticketmp.auth.mapper.SysRoleMapper;
import top.hetao.shiyuanticketmp.auth.mapper.SysUserMapper;
import top.hetao.shiyuanticketmp.auth.mapper.SysUserRoleMapper;
import top.hetao.shiyuanticketmp.common.context.TenantContext;
import top.hetao.shiyuanticketmp.workorder.exception.WorkOrderException;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import top.hetao.shiyuanticketmp.auth.controller.dto.SimpleUserDTO;

@Service
public class UserService extends ServiceImpl<SysUserMapper, SysUser> {

    private final SysRoleMapper roleMapper;
    private final SysPermissionMapper permissionMapper;
    private final SysUserRoleMapper userRoleMapper;
    private final PasswordEncoder passwordEncoder;

    public UserService(SysRoleMapper roleMapper, SysPermissionMapper permissionMapper,
                       SysUserRoleMapper userRoleMapper, PasswordEncoder passwordEncoder) {
        this.roleMapper = roleMapper;
        this.permissionMapper = permissionMapper;
        this.userRoleMapper = userRoleMapper;
        this.passwordEncoder = passwordEncoder;
    }

    @Transactional(readOnly = true)
    public SysUser getByUsername(String username) {
        return getOne(new LambdaQueryWrapper<SysUser>()
                .eq(SysUser::getUsername, username));
    }

    /**
     * 根据用户名查询用户（忽略租户过滤，用于登录）。
     *
     * @param username 用户名
     * @return 用户实体，不存在时返回 null
     */
    @Transactional(readOnly = true)
    public SysUser getByUsernameIgnoreTenant(String username) {
        return baseMapper.selectByUsernameIgnoreTenant(username);
    }

    @Transactional(readOnly = true)
    public SysUser getByIdIgnoreTenant(Long id) {
        return baseMapper.selectByIdIgnoreTenant(id);
    }

    @Transactional(readOnly = true)
    public SysUser getByExternalUserIdIgnoreTenant(String externalUserId) {
        return baseMapper.selectByExternalUserIdIgnoreTenant(externalUserId);
    }

    @Transactional(readOnly = true)
    public List<String> getRoleCodes(Long userId) {
        return roleMapper.selectRoleCodesByUserId(userId);
    }

    @Transactional(readOnly = true)
    public List<String> getPermissionCodes(Long userId) {
        return permissionMapper.selectPermissionCodesByUserId(userId);
    }

    @Transactional(readOnly = true)
    public IPage<SysUser> listPage(int page, int pageSize, String username, Integer status) {
        LambdaQueryWrapper<SysUser> wrapper = new LambdaQueryWrapper<>();
        if (username != null && !username.isBlank()) {
            wrapper.like(SysUser::getUsername, username);
        }
        if (status != null) {
            wrapper.eq(SysUser::getStatus, status);
        }
        wrapper.orderByDesc(SysUser::getCreatedAt);
        return page(new Page<>(page, pageSize), wrapper);
    }

    @Transactional
    public SysUser createUser(CreateUserRequest request) {
        if (getByUsername(request.getUsername()) != null) {
            throw new WorkOrderException("用户名已存在: " + request.getUsername());
        }
        String extId = normalizeBlank(request.getExternalUserId());
        if (extId != null) {
            SysUser existing = baseMapper.selectByExternalUserIdIgnoreTenant(extId);
            if (existing != null) {
                throw new WorkOrderException("外部用户ID已被其他用户绑定: " + extId);
            }
        }
        SysUser user = new SysUser();
        user.setUsername(request.getUsername());
        user.setPassword(passwordEncoder.encode(request.getPassword()));
        user.setNickname(request.getNickname());
        user.setPhone(normalizeBlank(request.getPhone()));
        user.setEmail(normalizeBlank(request.getEmail()));
        user.setExternalUserId(extId);
        user.setStatus(1);
        // 租户 ID：优先使用请求指定的值，否则使用当前租户上下文
        if (request.getTenantId() != null) {
            user.setTenantId(request.getTenantId());
        } else {
            Long currentTenant = TenantContext.getTenantId();
            user.setTenantId(currentTenant != null ? currentTenant : 0L);
        }
        save(user);
        // 如果请求中包含角色 ID，分配角色
        if (request.getRoleIds() != null && !request.getRoleIds().isEmpty()) {
            assignRoles(user.getId(), request.getRoleIds());
        }
        return user;
    }

    @Transactional
    public void updateUser(Long userId, UpdateUserRequest request) {
        SysUser user = getById(userId);
        if (user == null) {
            throw new WorkOrderException("用户不存在: " + userId);
        }
        if (request.getNickname() != null) user.setNickname(request.getNickname());
        if (request.getStatus() != null) user.setStatus(request.getStatus());

        // 处理 externalUserId：校验唯一性 + 空白转 null
        String newExtId = null; // null 表示不更新
        boolean clearExtId = false;
        if (request.getExternalUserId() != null) {
            newExtId = normalizeBlank(request.getExternalUserId());
            if (newExtId != null) {
                SysUser existing = baseMapper.selectByExternalUserIdIgnoreTenant(newExtId);
                if (existing != null && !existing.getId().equals(userId)) {
                    throw new WorkOrderException("外部用户ID已被其他用户绑定: " + newExtId);
                }
            }
            clearExtId = (newExtId == null); // 传了空字符串 → 清除
        }

        // 先用 updateById 更新非 null 字段（nickname、status 等）
        updateById(user);

        // 再用 LambdaUpdateWrapper 显式 SET NULL，绕过 MyBatis-Plus 默认的 null 跳过策略
        LambdaUpdateWrapper<SysUser> nullWrapper = new LambdaUpdateWrapper<>();
        boolean hasNullUpdate = false;
        if (request.getPhone() != null) {
            String val = normalizeBlank(request.getPhone());
            nullWrapper.set(SysUser::getPhone, val);
            hasNullUpdate = true;
        }
        if (request.getEmail() != null) {
            String val = normalizeBlank(request.getEmail());
            nullWrapper.set(SysUser::getEmail, val);
            hasNullUpdate = true;
        }
        if (clearExtId) {
            nullWrapper.set(SysUser::getExternalUserId, null);
            hasNullUpdate = true;
        } else if (newExtId != null) {
            nullWrapper.set(SysUser::getExternalUserId, newExtId);
            hasNullUpdate = true;
        }
        if (hasNullUpdate) {
            nullWrapper.eq(SysUser::getId, userId);
            update(nullWrapper);
        }
    }

    /**
     * 管理员更新用户（Map 版本，支持 JSON null 检测）。
     *
     * <p>使用 Map 接收请求体，通过 {@code containsKey()} 区分"字段缺失"和"字段值为 null"：
     * <ul>
     *   <li>字段缺失 → 不更新，保持原值</li>
     *   <li>字段值为 null 或空字符串 → 清除（SET NULL）</li>
     *   <li>字段有值 → 正常更新</li>
     * </ul>
     */
    @Transactional
    @SuppressWarnings("unchecked")
    public void updateUserFromMap(Long userId, Map<String, Object> fields) {
        SysUser user = getById(userId);
        if (user == null) {
            throw new WorkOrderException("用户不存在: " + userId);
        }
        // nickname / status 用 updateById
        if (fields.containsKey("nickname") && fields.get("nickname") != null) {
            user.setNickname((String) fields.get("nickname"));
        }
        if (fields.containsKey("status") && fields.get("status") != null) {
            Object statusVal = fields.get("status");
            if (statusVal instanceof Number) {
                user.setStatus(((Number) statusVal).intValue());
            }
        }

        // 可选字段（phone / email / externalUserId）用 LambdaUpdateWrapper 显式 SET
        LambdaUpdateWrapper<SysUser> wrapper = new LambdaUpdateWrapper<>();
        boolean hasWrapperUpdate = false;

        if (fields.containsKey("phone")) {
            String val = normalizeBlank((String) fields.get("phone"));
            wrapper.set(SysUser::getPhone, val);
            hasWrapperUpdate = true;
        }
        if (fields.containsKey("email")) {
            String val = normalizeBlank((String) fields.get("email"));
            wrapper.set(SysUser::getEmail, val);
            hasWrapperUpdate = true;
        }
        if (fields.containsKey("externalUserId")) {
            String rawExtId = (String) fields.get("externalUserId");
            String newExtId = normalizeBlank(rawExtId);
            if (newExtId != null) {
                SysUser existing = baseMapper.selectByExternalUserIdIgnoreTenant(newExtId);
                if (existing != null && !existing.getId().equals(userId)) {
                    throw new WorkOrderException("外部用户ID已被其他用户绑定: " + newExtId);
                }
            }
            wrapper.set(SysUser::getExternalUserId, newExtId);
            hasWrapperUpdate = true;
        }
        if (fields.containsKey("tenantId")) {
            Object tenantVal = fields.get("tenantId");
            Long tenantId = null;
            if (tenantVal instanceof Number) {
                tenantId = ((Number) tenantVal).longValue();
            } else if (tenantVal instanceof String s && !s.isBlank()) {
                tenantId = Long.parseLong(s);
            }
            wrapper.set(SysUser::getTenantId, tenantId);
            hasWrapperUpdate = true;
        }

        // 先用 updateById 更新 nickname / status
        updateById(user);

        // 再用 wrapper 更新可选字段（包括显式 SET NULL）
        if (hasWrapperUpdate) {
            wrapper.eq(SysUser::getId, userId);
            update(wrapper);
        }
    }

    @Transactional
    public void resetPassword(Long userId, String newPassword) {
        SysUser user = getById(userId);
        if (user == null) {
            throw new WorkOrderException("用户不存在: " + userId);
        }
        user.setPassword(passwordEncoder.encode(newPassword));
        updateById(user);
    }

    @Transactional
    public void assignRoles(Long userId, List<Long> roleIds) {
        SysUser user = getById(userId);
        if (user == null) {
            throw new WorkOrderException("用户不存在: " + userId);
        }
        userRoleMapper.delete(new LambdaQueryWrapper<SysUserRole>()
                .eq(SysUserRole::getUserId, userId));
        for (Long roleId : roleIds) {
            SysUserRole ur = new SysUserRole();
            ur.setUserId(userId);
            ur.setRoleId(roleId);
            userRoleMapper.insert(ur);
        }
    }

    @Transactional(readOnly = true)
    public List<Long> getUserRoleIds(Long userId) {
        return userRoleMapper.selectRoleIdsByUserId(userId);
    }

    @Transactional(readOnly = true)
    public List<SimpleUserDTO> listSimpleUsers() {
        List<SysUser> users = list(new LambdaQueryWrapper<SysUser>()
                .select(SysUser::getId, SysUser::getUsername, SysUser::getNickname)
                .eq(SysUser::getStatus, 1)
                .orderByAsc(SysUser::getUsername));
        return users.stream()
                .map(user -> {
                    SimpleUserDTO dto = new SimpleUserDTO();
                    dto.setId(user.getId());
                    dto.setUsername(user.getUsername());
                    dto.setNickname(user.getNickname());
                    return dto;
                })
                .collect(Collectors.toList());
    }

    private static String normalizeBlank(String value) {
        return (value == null || value.isBlank()) ? null : value;
    }
}
