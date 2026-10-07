package com.xueji.agent.service.impl;

import com.xueji.agent.domain.entity.UserProfile;
import com.xueji.agent.mapper.UserProfileMapper;
import com.xueji.agent.service.ProfileService;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;

@Service
public class ProfileServiceImpl implements ProfileService {

    @Resource
    private UserProfileMapper profileMapper;

    @Override
    public UserProfile getByUser(Long userId) {
        return profileMapper.selectById(userId);
    }

    @Override
    public UserProfile save(Long userId, UserProfile profile) {
        profile.setUserId(userId);
        profile.setUpdatedAt(LocalDateTime.now());
        if (profileMapper.selectById(userId) == null) {
            profileMapper.insert(profile);
        } else {
            profileMapper.updateById(profile);
        }
        return profile;
    }
}
