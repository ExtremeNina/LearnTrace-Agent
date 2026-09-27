package com.xueji.agent.controller;

import cn.dev33.satoken.stp.SaTokenInfo;
import cn.dev33.satoken.stp.StpUtil;
import com.xueji.agent.common.Result;
import com.xueji.agent.domain.dto.LoginDto;
import com.xueji.agent.domain.dto.RegisterDto;
import com.xueji.agent.domain.entity.User;
import com.xueji.agent.service.AuthService;
import jakarta.annotation.Resource;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 认证接口：注册 / 登录 / 登出 / 当前用户
 */
@RequestMapping("/auth")
@RestController
public class AuthController {

    @Resource
    private AuthService authService;

    /**
     * 注册（最简版：用户名 + 密码）
     */
    @PostMapping("/register")
    public Result<Void> register(@Valid @RequestBody RegisterDto request) {
        authService.register(request);
        return Result.ok("注册成功");
    }

    /**
     * 账密登录
     */
    @PostMapping("/login")
    public Result<SaTokenInfo> login(@Valid @RequestBody LoginDto request) {
        return Result.data(authService.login(request));
    }

    /**
     * 登出
     */
    @PostMapping("/logout")
    public Result<Void> logout() {
        StpUtil.logout();
        return Result.ok("已退出登录");
    }

    /**
     * 当前登录用户信息
     */
    @GetMapping("/info")
    public Result<User> info() {
        return Result.data(authService.getCurrentUser());
    }
}
