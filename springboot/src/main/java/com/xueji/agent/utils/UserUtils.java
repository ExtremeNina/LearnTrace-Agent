package com.xueji.agent.utils;

import cn.dev33.satoken.stp.StpUtil;

/**
 * 用户工具类：获取当前登录用户 ID
 */
public class UserUtils {

    /**
     * 获取当前登录用户 ID
     */
    public static Long getCurrentLoginId() {
        return StpUtil.getLoginIdAsLong();
    }
}
