package com.xueji.agent.controller;

import cn.dev33.satoken.stp.StpUtil;
import com.xueji.agent.common.Result;
import com.xueji.agent.domain.dto.ChangePasswordDto;
import com.xueji.agent.domain.dto.DeleteAccountDto;
import com.xueji.agent.domain.dto.UpdatePreferenceDto;
import com.xueji.agent.domain.dto.UpdateProfileDto;
import com.xueji.agent.domain.vo.UserProfileVO;
import com.xueji.agent.service.UserService;
import com.xueji.agent.utils.UserUtils;
import jakarta.annotation.Resource;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 个人页面接口：资料 / 偏好设置 / 修改密码 / 注销账号
 */
@RequestMapping("/users")
@RestController
public class UserController {

    @Resource
    private UserService userService;

    /** 当前用户资料 */
    @GetMapping("/me")
    public Result<UserProfileVO> me() {
        return Result.data(userService.getProfile(UserUtils.getCurrentLoginId()));
    }

    /** 编辑资料（昵称必填，邮箱 / 简介 / 头像选填） */
    @PutMapping("/me")
    public Result<UserProfileVO> updateMe(@RequestBody UpdateProfileDto dto) {
        return Result.data(userService.updateProfile(UserUtils.getCurrentLoginId(), dto));
    }

    /** 偏好设置（主题 / 任务通知开关） */
    @PutMapping("/me/preferences")
    public Result<UserProfileVO> updatePreferences(@RequestBody UpdatePreferenceDto dto) {
        return Result.data(userService.updatePreferences(UserUtils.getCurrentLoginId(), dto));
    }

    /** 修改密码（校验原密码） */
    @PutMapping("/me/password")
    public Result<Void> changePassword(@RequestBody ChangePasswordDto dto) {
        userService.changePassword(UserUtils.getCurrentLoginId(), dto);
        return Result.ok("密码已修改");
    }

    /**
     * 注销账号（密码确认，逻辑删除）；注销后登出当前会话
     */
    @PostMapping("/me/delete")
    public Result<Void> deleteAccount(@RequestBody DeleteAccountDto dto) {
        Long userId = UserUtils.getCurrentLoginId();
        userService.deleteAccount(userId, dto.getPassword());
        StpUtil.logout();
        return Result.ok("账号已注销");
    }
}
