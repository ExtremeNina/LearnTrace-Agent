package com.xueji.agent.service;

import com.xueji.agent.domain.entity.UserProfile;

/**
 * 学习者画像（B26 阶段 3）：GET/PUT 语义，未填写返回 NULL
 */
public interface ProfileService {

    UserProfile getByUser(Long userId);

    UserProfile save(Long userId, UserProfile profile);
}
