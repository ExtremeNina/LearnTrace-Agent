package com.xueji.agent.service.impl;

import cn.dev33.satoken.stp.SaTokenInfo;
import cn.dev33.satoken.stp.StpUtil;
import cn.hutool.crypto.digest.BCrypt;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.xueji.agent.domain.dto.LoginDto;
import com.xueji.agent.domain.dto.RegisterDto;
import com.xueji.agent.domain.entity.User;
import com.xueji.agent.exception.BusinessException;
import com.xueji.agent.mapper.UserMapper;
import com.xueji.agent.service.AuthService;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Service;

@Service
public class AuthServiceImpl implements AuthService {

    @Resource
    private UserMapper userMapper;

    @Override
    public void register(RegisterDto request) {
        Long exists = userMapper.selectCount(new QueryWrapper<User>().eq("username", request.getUsername()));
        if (exists != null && exists > 0) {
            throw new BusinessException("用户名已存在");
        }
        User user = new User()
                .setUsername(request.getUsername())
                .setPasswordHash(BCrypt.hashpw(request.getPassword(), BCrypt.gensalt()))
                .setNickname(request.getUsername());
        userMapper.insert(user);
    }

    @Override
    public SaTokenInfo login(LoginDto request) {
        User user = userMapper.selectOne(new QueryWrapper<User>().eq("username", request.getUsername()));
        if (user == null || !BCrypt.checkpw(request.getPassword(), user.getPasswordHash())) {
            throw new BusinessException("用户名或密码错误");
        }
        StpUtil.login(user.getId());
        return StpUtil.getTokenInfo();
    }

    @Override
    public User getCurrentUser() {
        return userMapper.selectById(StpUtil.getLoginIdAsLong());
    }
}
