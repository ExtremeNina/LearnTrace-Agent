package com.xueji.agent.service;

import com.xueji.agent.domain.dto.ChangePasswordDto;
import com.xueji.agent.domain.dto.UpdatePreferenceDto;
import com.xueji.agent.domain.dto.UpdateProfileDto;
import com.xueji.agent.domain.vo.UserProfileVO;

/**
 * 用户个人页面服务：资料 / 偏好设置 / 修改密码 / 注销账号
 */
public interface UserService {

    /** 当前用户资料 */
    UserProfileVO getProfile(Long userId);

    /** 编辑资料：昵称必填，邮箱 / 简介 / 头像选填 */
    UserProfileVO updateProfile(Long userId, UpdateProfileDto dto);

    /** 偏好设置：主题（LIGHT/DARK）与任务通知开关，仅提供的字段会被更新 */
    UserProfileVO updatePreferences(Long userId, UpdatePreferenceDto dto);

    /** 修改密码（校验原密码） */
    void changePassword(Long userId, ChangePasswordDto dto);

    /** 注销账号（密码确认后逻辑删除），返回前调用方需登出会话 */
    void deleteAccount(Long userId, String password);
}
