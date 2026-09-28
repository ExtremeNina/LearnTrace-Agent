package com.xueji.agent.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * 流水线专用线程池（agent.md 规范：禁 @Async，线程池统一定义、显式提交）。
 * 用途：批量帧 OCR 的并发提交与轮询、流水线内部阶段并行。
 */
@Configuration
public class CourseExecutorConfig {

    @Bean(name = "courseExecutor")
    public ThreadPoolExecutor courseExecutor() {
        return new ThreadPoolExecutor(
                4,
                8,
                60L,
                TimeUnit.SECONDS,
                new LinkedBlockingQueue<>(200),
                r -> new Thread(r, "course-pipeline-" + r.hashCode() % 100),
                new ThreadPoolExecutor.CallerRunsPolicy()
        );
    }
}
