package com.xueji.agent.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.util.Arrays;

/**
 * 跨域解决类。
 * 生产容器部署下前端经 nginx 同源反代，不触发 CORS；本地开发默认放行 5173，
 * 可通过环境变量 CORS_ALLOWED_ORIGINS（逗号分隔）扩展。
 *
 * @author hepingan
 * @since 2025/3/9
 */
@Configuration
public class CorsConfig {

    /** 允许的前端来源（逗号分隔）；空值表示不注册 CORS 规则（同源部署无需跨域） */
    @Value("${cors.allowed-origins:http://localhost:5173}")
    private String allowedOrigins;

    @Bean
    public WebMvcConfigurer corsConfigurer() {
        String[] origins = Arrays.stream(allowedOrigins.split(","))
                .map(String::trim)
                .filter(origin -> !origin.isEmpty())
                .toArray(String[]::new);
        return new WebMvcConfigurer() {
            @Override
            public void addCorsMappings(CorsRegistry registry) {
                if (origins.length == 0) {
                    return;
                }
                registry.addMapping("/**")
                        .allowedOrigins(origins)
                        .allowedMethods("GET", "POST", "PUT", "DELETE", "OPTIONS")
                        .allowedHeaders("*")
                        .allowCredentials(true);
            }
        };
    }
}
