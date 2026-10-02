package com.xueji.agent.task;

import com.xueji.agent.ai.RagIngestService;
import com.xueji.agent.common.RedisKeys;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;

/**
 * RAG 向量化补漏定时任务（PRD §3.6）：摄取失败（网络 / 服务不可用）的学习资料
 * 由水位之后有变动的记录覆盖重摄，保证向量库最终与事实源一致。
 * 水位存 Redis；缺失（首次运行 / 应用侧 Redis 被清）时全量回填，按文档 ID 幂等。
 */
@Slf4j
@Component
public class RagRepairScheduler {

    @Resource
    private RagIngestService ragIngestService;

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    /** 启动 20 秒后首跑（首跑即全量回填），此后每 3 小时一轮 */
    @Scheduled(initialDelay = 20_000, fixedDelay = 3 * 3600 * 1000L)
    public void repair() {
        try {
            String raw = stringRedisTemplate.opsForValue().get(RedisKeys.RAG_REPAIR_CHECKPOINT);
            LocalDateTime checkpoint = raw == null || raw.isBlank() ? null : LocalDateTime.parse(raw);
            // 水位取本轮开始时间：本轮执行期间发生变动的资料由下一轮覆盖，摄取幂等
            LocalDateTime nextCheckpoint = LocalDateTime.now();
            int processed = ragIngestService.repairSince(checkpoint);
            stringRedisTemplate.opsForValue().set(RedisKeys.RAG_REPAIR_CHECKPOINT, nextCheckpoint.toString());
            log.info("RAG 向量化补漏完成, checkpoint={}, 处理 {} 条", checkpoint, processed);
        } catch (Exception e) {
            log.error("RAG 向量化补漏任务失败（下一轮重试）", e);
        }
    }
}
