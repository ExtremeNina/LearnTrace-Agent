package com.xueji.agent.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.xueji.agent.domain.entity.UserProfile;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface UserProfileMapper extends BaseMapper<UserProfile> {
}
