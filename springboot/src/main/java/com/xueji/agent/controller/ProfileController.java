package com.xueji.agent.controller;

import com.xueji.agent.common.Result;
import com.xueji.agent.domain.entity.UserProfile;
import com.xueji.agent.service.ProfileService;
import com.xueji.agent.utils.UserUtils;
import jakarta.annotation.Resource;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 学习者画像（B26 阶段 3）：多角色评审与笔记生成的难度适配输入
 */
@RestController
@RequestMapping("/profile")
public class ProfileController {

    @Resource
    private ProfileService profileService;

    @GetMapping
    public Result<UserProfile> get() {
        return Result.data(profileService.getByUser(UserUtils.getCurrentLoginId()));
    }

    @PutMapping
    public Result<UserProfile> save(@RequestBody UserProfile profile) {
        return Result.data(profileService.save(UserUtils.getCurrentLoginId(), profile));
    }
}
