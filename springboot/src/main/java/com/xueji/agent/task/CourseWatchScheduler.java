package com.xueji.agent.task;

import com.xueji.agent.service.CourseService;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 网课看护定时任务：流水线进程崩溃等中断场景下，MQ 消息不重投，
 * 网课会永远停留在 PROCESSING（retry 只允许 FAILED）——定时把超时的处理中网课自愈为 FAILED，
 * 让用户可以手动重试
 */
@Slf4j
@Component
public class CourseWatchScheduler {

    /** PROCESSING 超过该时长视为任务中断（正常流水线 10 分钟级视频约 3~8 分钟） */
    private static final long STALE_MINUTES = 60;

    @Resource
    private CourseService courseService;

    /** 启动 1 分钟后首跑，此后每 10 分钟一轮 */
    @Scheduled(initialDelay = 60_000, fixedDelay = 600_000L)
    public void failStaleProcessingCourses() {
        try {
            courseService.failStaleProcessing(STALE_MINUTES);
        } catch (Exception e) {
            log.error("网课处理超时自愈任务失败", e);
        }
    }
}
