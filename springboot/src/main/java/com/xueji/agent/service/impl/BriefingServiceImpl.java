package com.xueji.agent.service.impl;

import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.xueji.agent.ai.prompt.AgentPrompts;
import com.xueji.agent.domain.entity.DailyBriefing;
import com.xueji.agent.domain.vo.BriefingVO;
import com.xueji.agent.exception.BusinessException;
import com.xueji.agent.mapper.DailyBriefingMapper;
import com.xueji.agent.config.ChatClientFactory;
import com.xueji.agent.service.AiModelService;
import com.xueji.agent.service.BriefingService;
import com.xueji.agent.service.LearningStatsService;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;

/**
 * 每日学习简报实现：
 * 统计快照由 LearningStatsService 聚合（确定性查询），LLM 只做归纳与措辞（把数字翻译成诊断与建议），
 * 不做查询、不编造统计里没有的结论。
 * 惰性生成：当天首次访问触发 LLM 并落库缓存（UNIQUE(user_id, brief_date)），当天再次访问读缓存。
 */
@Slf4j
@Service
public class BriefingServiceImpl implements BriefingService {

    @Resource
    private DailyBriefingMapper dailyBriefingMapper;

    @Resource
    private LearningStatsService learningStatsService;

    @Resource
    private AiModelService aiModelService;

    @Override
    public BriefingVO getTodayBriefing(Long userId) {
        LocalDate today = LocalDate.now();
        DailyBriefing row = findRow(userId, today);
        if (row != null) {
            return toVO(row);
        }
        return generateAndSave(userId, today);
    }

    @Override
    public BriefingVO refreshTodayBriefing(Long userId) {
        return generateAndSave(userId, LocalDate.now());
    }

    @Override
    public Map<String, Object> getStatsSnapshot(Long userId) {
        return learningStatsService.getStatsSnapshot(userId);
    }

    private BriefingVO generateAndSave(Long userId, LocalDate today) {
        Map<String, Object> stats = learningStatsService.getStatsSnapshot(userId);
        String statsJson = JSONUtil.toJsonStr(stats);
        String content = generateContent(userId, today, stats);

        DailyBriefing row = findRow(userId, today);
        if (row == null) {
            row = new DailyBriefing()
                    .setUserId(userId)
                    .setBriefDate(today)
                    .setStatsJson(statsJson)
                    .setContent(content)
                    .setCreatedAt(LocalDateTime.now())
                    .setUpdatedAt(LocalDateTime.now());
            dailyBriefingMapper.insert(row);
        } else {
            row.setStatsJson(statsJson).setContent(content).setUpdatedAt(LocalDateTime.now());
            dailyBriefingMapper.updateById(row);
        }
        log.info("每日简报已生成, userId={}, date={}, 字数={}", userId, today, content.length());
        return toVO(row);
    }

    private String generateContent(Long userId, LocalDate today, Map<String, Object> stats) {
        ChatClient chatClient = aiModelService.resolve(userId,
                AiModelService.MODULE_BRIEFING, ChatClientFactory.Variant.GENERATION);
        String content = chatClient.prompt()
                .system(AgentPrompts.BRIEFING_PROMPT)
                .user(buildUserContent(stats))
                .call()
                .content();
        if (content == null || content.isBlank()) {
            throw new BusinessException("简报生成失败：LLM 未返回内容");
        }
        return content;
    }

    private DailyBriefing findRow(Long userId, LocalDate today) {
        return dailyBriefingMapper.selectOne(new QueryWrapper<DailyBriefing>()
                .eq("user_id", userId)
                .eq("brief_date", today));
    }

    /**
     * 简报的用户消息：结构化统计 JSON（公开静态方法，便于单元测试）
     */
    public static String buildUserContent(Map<String, Object> stats) {
        return "以下是用户本周的学习统计数据（JSON）：\n" + JSONUtil.toJsonStr(stats);
    }

    private BriefingVO toVO(DailyBriefing row) {
        Map<String, Object> stats = new HashMap<>();
        if (row.getStatsJson() != null && !row.getStatsJson().isBlank()) {
            try {
                stats.putAll(JSONUtil.parseObj(row.getStatsJson()));
            } catch (Exception e) {
                log.warn("简报统计快照解析失败, briefingId={}", row.getId());
            }
        }
        return new BriefingVO()
                .setBriefDate(row.getBriefDate().toString())
                .setContent(row.getContent())
                .setStats(stats)
                .setGeneratedAt(row.getUpdatedAt());
    }
}
