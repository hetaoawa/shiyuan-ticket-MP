package top.hetao.shiyuanticketmp.auth.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
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
import top.hetao.shiyuanticketmp.workorder.exception.WorkOrderException;

import java.util.List;

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
        SysUser user = new SysUser();
        user.setUsername(request.getUsername());
        user.setPassword(passwordEncoder.encode(request.getPassword()));
        user.setNickname(request.getNickname());
        user.setPhone(request.getPhone());
        user.setEmail(request.getEmail());
        user.setStatus(1);
        save(user);
        return user;
    }

    @Transactional
    public void updateUser(Long userId, UpdateUserRequest request) {
        SysUser user = getById(userId);
        if (user == null) {
            throw new WorkOrderException("用户不存在: " + userId);
        }
        if (request.getNickname() != null) user.setNickname(request.getNickname());
        if (request.getPhone() != null) user.setPhone(request.getPhone());
        if (request.getEmail() != null) user.setEmail(request.getEmail());
        if (request.getStatus() != null) user.setStatus(request.getStatus());
        updateById(user);
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
}
