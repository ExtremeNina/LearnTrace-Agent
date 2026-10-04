package com.xueji.agent.service.impl;

import cn.hutool.crypto.digest.BCrypt;
import com.xueji.agent.domain.dto.ChangePasswordDto;
import com.xueji.agent.domain.dto.UpdatePreferenceDto;
import com.xueji.agent.domain.dto.UpdateProfileDto;
import com.xueji.agent.domain.entity.User;
import com.xueji.agent.domain.vo.UserProfileVO;
import com.xueji.agent.exception.BusinessException;
import com.xueji.agent.mapper.UserMapper;
import com.xueji.agent.service.AiModelService;
import com.xueji.agent.service.UserService;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;

/**
 * 用户个人页面实现：资料 / 偏好 / 密码 / 注销。
 * 注销为逻辑删除，学习资产数据保留（PRD：学习资产与账号生命周期解耦，当前阶段不做数据清除）
 */
@Slf4j
@Service
public class UserServiceImpl implements UserService {

    private static final String THEME_LIGHT = "LIGHT";
    private static final String THEME_DARK = "DARK";

    @Resource
    private UserMapper userMapper;

    @Resource
    private AiModelService aiModelService;

    @Override
    public UserProfileVO getProfile(Long userId) {
        return toVO(ownedUser(userId));
    }

    @Override
    public UserProfileVO updateProfile(Long userId, UpdateProfileDto dto) {
        User user = ownedUser(userId);
        if (dto.getNickname() == null || dto.getNickname().isBlank()) {
            throw new BusinessException("昵称不能为空");
        }
        user.setNickname(dto.getNickname().trim())
                .setEmail(trimToNull(dto.getEmail()))
                .setBio(trimToNull(dto.getBio()))
                .setAvatarUrl(trimToNull(dto.getAvatarUrl()))
                .setUpdatedAt(LocalDateTime.now());
        userMapper.updateById(user);
        return toVO(user);
    }

    @Override
    public UserProfileVO updatePreferences(Long userId, UpdatePreferenceDto dto) {
        User user = ownedUser(userId);
        if (dto.getTheme() != null) {
            if (!THEME_LIGHT.equals(dto.getTheme()) && !THEME_DARK.equals(dto.getTheme())) {
                throw new BusinessException("不支持的主题");
            }
            user.setTheme(dto.getTheme());
        }
        if (dto.getNotifyTaskEnabled() != null) {
            user.setNotifyTaskEnabled(Boolean.TRUE.equals(dto.getNotifyTaskEnabled()) ? 1 : 0);
        }
        user.setUpdatedAt(LocalDateTime.now());
        userMapper.updateById(user);
        return toVO(user);
    }

    @Override
    public void changePassword(Long userId, ChangePasswordDto dto) {
        User user = ownedUser(userId);
        if (dto.getOldPassword() == null || dto.getOldPassword().isBlank()
                || !BCrypt.checkpw(dto.getOldPassword(), user.getPasswordHash())) {
            throw new BusinessException("原密码不正确");
        }
        if (dto.getNewPassword() == null || dto.getNewPassword().length() < 6) {
            throw new BusinessException("新密码至少 6 位");
        }
        user.setPasswordHash(BCrypt.hashpw(dto.getNewPassword(), BCrypt.gensalt()))
                .setUpdatedAt(LocalDateTime.now());
        userMapper.updateById(user);
        log.info("用户修改密码, userId={}", userId);
    }

    @Override
    public void deleteAccount(Long userId, String password) {
        User user = ownedUser(userId);
        if (password == null || password.isBlank() || !BCrypt.checkpw(password, user.getPasswordHash())) {
            throw new BusinessException("密码不正确，账号未注销");
        }
        user.setDeleted(1).setUpdatedAt(LocalDateTime.now());
        userMapper.updateById(user);
        // 模型配置含 API Key（凭据非资产），注销时物理删除
        aiModelService.deleteAllByUser(userId);
        log.info("账号已注销（逻辑删除）, userId={}", userId);
    }

    private User ownedUser(Long userId) {
        User user = userMapper.selectById(userId);
        if (user == null || Integer.valueOf(1).equals(user.getDeleted())) {
            throw new BusinessException(404, "用户不存在");
        }
        return user;
    }

    private String trimToNull(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim();
    }

    private UserProfileVO toVO(User user) {
        return new UserProfileVO()
                .setId(user.getId())
                .setUsername(user.getUsername())
                .setNickname(user.getNickname())
                .setEmail(user.getEmail())
                .setBio(user.getBio())
                .setAvatarUrl(user.getAvatarUrl())
                .setTheme(user.getTheme() == null ? THEME_LIGHT : user.getTheme())
                .setNotifyTaskEnabled(!Integer.valueOf(0).equals(user.getNotifyTaskEnabled()))
                .setCreatedAt(user.getCreatedAt());
    }
}
