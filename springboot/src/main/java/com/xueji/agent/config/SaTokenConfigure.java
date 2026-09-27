package com.xueji.agent.config;

import cn.dev33.satoken.interceptor.SaInterceptor;
import cn.dev33.satoken.stp.StpUtil;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Sa-Token 拦截配置：除注册/登录外全部需要登录
 */
@Configuration
public class SaTokenConfigure implements WebMvcConfigurer {

    /**
     * 无需登录即可访问的路径（/ws/agent 在握手拦截器中单独鉴权）
     */
    private static final String[] EXCLUDE_PATHS = new String[]{
            "/auth/login",
            "/auth/register",
            "/ws/agent"
    };

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(new SaInterceptor(handler -> {
            // CORS 预检请求（OPTIONS）不携带 token，跳过登录校验，否则浏览器跨域必然失败
            if (!"OPTIONS".equalsIgnoreCase(cn.dev33.satoken.context.SaHolder.getRequest().getMethod())) {
                StpUtil.checkLogin();
            }
        })).addPathPatterns("/**")
                .excludePathPatterns(EXCLUDE_PATHS);
    }
}
