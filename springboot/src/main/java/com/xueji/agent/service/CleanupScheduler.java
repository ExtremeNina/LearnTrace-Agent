package com.xueji.agent.service;

import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 定时清理任务：会话属于临时数据，超过保留期未活跃的会话由系统自动清理
 * （连带消息与 Redis 会话记忆；学习资产不受影响）
 */
@Slf4j
@Component
public class CleanupScheduler {

    @Resource
    private ConversationService conversationService;

    /** 每天凌晨 3 点执行 */
    @Scheduled(cron = "0 0 3 * * ?")
    public void cleanupExpiredConversations() {
        int removed = conversationService.cleanupExpiredConversations();
        if (removed > 0) {
            log.info("会话定时清理完成, 删除 {} 个超过 {} 天未活跃的会话", removed, ConversationService.RETENTION_DAYS);
        }
    }
}
