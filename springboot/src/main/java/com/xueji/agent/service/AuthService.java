package com.xueji.agent.service;

import cn.dev33.satoken.stp.SaTokenInfo;
import com.xueji.agent.domain.dto.LoginDto;
import com.xueji.agent.domain.dto.RegisterDto;
import com.xueji.agent.domain.entity.User;

/**
 * 认证服务：注册 / 登录 / 当前用户
 */
public interface AuthService {

    /**
     * 注册（最简版：用户名 + 密码）
     */
    void register(RegisterDto request);

    /**
     * 账密登录，返回 Sa-Token 令牌信息
     */
    SaTokenInfo login(LoginDto request);

    /**
     * 获取当前登录用户信息
     */
    User getCurrentUser();
}
