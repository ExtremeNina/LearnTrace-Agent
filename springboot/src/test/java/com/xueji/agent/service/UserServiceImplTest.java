package com.xueji.agent.service;

import com.xueji.agent.domain.dto.ChangePasswordDto;
import com.xueji.agent.domain.dto.UpdatePreferenceDto;
import com.xueji.agent.domain.dto.UpdateProfileDto;
import com.xueji.agent.domain.entity.User;
import com.xueji.agent.domain.vo.UserProfileVO;
import com.xueji.agent.exception.BusinessException;
import com.xueji.agent.mapper.UserMapper;
import com.xueji.agent.service.impl.UserServiceImpl;
import cn.hutool.crypto.digest.BCrypt;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 个人页面服务：资料编辑、偏好设置、修改密码、注销账号
 */
class UserServiceImplTest {

    private UserMapper userMapper;
    private UserServiceImpl service;

    @BeforeEach
    void setUp() {
        userMapper = mock(UserMapper.class);
        service = new UserServiceImpl();
        ReflectionTestUtils.setField(service, "userMapper", userMapper);
    }

    private User existingUser() {
        return new User().setId(5L).setUsername("admin").setNickname("演示管理员")
                .setPasswordHash(BCrypt.hashpw("old123456", BCrypt.gensalt()))
                .setTheme("LIGHT").setNotifyTaskEnabled(1).setDeleted(0);
    }

    @Test
    void getProfile_shouldMapVoWithoutPassword() {
        when(userMapper.selectById(5L)).thenReturn(existingUser());

        UserProfileVO vo = service.getProfile(5L);

        assertEquals(5L, vo.getId());
        assertEquals("admin", vo.getUsername());
        assertEquals("演示管理员", vo.getNickname());
        assertEquals("LIGHT", vo.getTheme());
        assertTrue(vo.getNotifyTaskEnabled());
    }

    @Test
    void updateProfile_shouldTrimAndBlankToNull() {
        when(userMapper.selectById(5L)).thenReturn(existingUser());
        when(userMapper.updateById(any(User.class))).thenReturn(1);

        UpdateProfileDto dto = new UpdateProfileDto();
        dto.setNickname("  新昵称  ");
        dto.setEmail("  me@xueji.com ");
        dto.setBio("   ");
        dto.setAvatarUrl("https://oss.example.com/a.png");

        UserProfileVO vo = service.updateProfile(5L, dto);

        assertEquals("新昵称", vo.getNickname());
        assertEquals("me@xueji.com", vo.getEmail());
        assertNull(vo.getBio());
        assertEquals("https://oss.example.com/a.png", vo.getAvatarUrl());
    }

    @Test
    void updateProfile_blankNicknameShouldReject() {
        when(userMapper.selectById(5L)).thenReturn(existingUser());

        UpdateProfileDto dto = new UpdateProfileDto();
        dto.setNickname("   ");

        assertThrows(BusinessException.class, () -> service.updateProfile(5L, dto));
    }

    @Test
    void updatePreferences_shouldValidateTheme() {
        when(userMapper.selectById(5L)).thenReturn(existingUser());

        UpdatePreferenceDto bad = new UpdatePreferenceDto();
        bad.setTheme("BLUE");
        assertThrows(BusinessException.class, () -> service.updatePreferences(5L, bad));
    }

    @Test
    void updatePreferences_shouldApplyDarkAndNotifyOff() {
        User user = existingUser();
        when(userMapper.selectById(5L)).thenReturn(user);
        when(userMapper.updateById(any(User.class))).thenReturn(1);

        UpdatePreferenceDto dto = new UpdatePreferenceDto();
        dto.setTheme("DARK");
        dto.setNotifyTaskEnabled(false);
        UserProfileVO vo = service.updatePreferences(5L, dto);

        assertEquals("DARK", vo.getTheme());
        assertFalse(vo.getNotifyTaskEnabled());
        assertEquals("DARK", user.getTheme());
        assertEquals(0, user.getNotifyTaskEnabled());
    }

    @Test
    void changePassword_shouldVerifyOldAndHashNew() {
        User user = existingUser();
        when(userMapper.selectById(5L)).thenReturn(user);
        when(userMapper.updateById(any(User.class))).thenReturn(1);

        ChangePasswordDto dto = new ChangePasswordDto();
        dto.setOldPassword("old123456");
        dto.setNewPassword("new654321");
        service.changePassword(5L, dto);

        ArgumentCaptor<User> captor = ArgumentCaptor.forClass(User.class);
        verify(userMapper).updateById(captor.capture());
        assertTrue(BCrypt.checkpw("new654321", captor.getValue().getPasswordHash()));
    }

    @Test
    void changePassword_wrongOldPasswordShouldReject() {
        when(userMapper.selectById(5L)).thenReturn(existingUser());

        ChangePasswordDto dto = new ChangePasswordDto();
        dto.setOldPassword("wrong");
        dto.setNewPassword("new654321");
        assertThrows(BusinessException.class, () -> service.changePassword(5L, dto));
    }

    @Test
    void changePassword_shortNewPasswordShouldReject() {
        when(userMapper.selectById(5L)).thenReturn(existingUser());

        ChangePasswordDto dto = new ChangePasswordDto();
        dto.setOldPassword("old123456");
        dto.setNewPassword("123");
        assertThrows(BusinessException.class, () -> service.changePassword(5L, dto));
    }

    @Test
    void deleteAccount_shouldVerifyPasswordAndSoftDelete() {
        User user = existingUser();
        when(userMapper.selectById(5L)).thenReturn(user);
        when(userMapper.updateById(any(User.class))).thenReturn(1);

        service.deleteAccount(5L, "old123456");

        assertEquals(1, user.getDeleted());
    }

    @Test
    void deleteAccount_wrongPasswordShouldReject() {
        when(userMapper.selectById(5L)).thenReturn(existingUser());

        assertThrows(BusinessException.class, () -> service.deleteAccount(5L, "wrong"));

        assertEquals(0, existingUser().getDeleted());
    }
}
