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
import java.util.List;
import java.util.Map;

/**
 * 每日学习简报实现：
 * 统计快照由 LearningStatsService 聚合（确定性查询），LLM 只做归纳与措辞（把数字翻译成诊断与建议），
 * 不做查询、不编造统计里没有的结论。
 * 惰性生成：按（用户 × 会话 × 日期）幂等——当天该会话首次访问触发 LLM 并落库缓存，再次访问读缓存；
 * 同一天不同会话复用同一份内容（不重复调用 LLM）；同一会话跨天使用则每天一条记录；
 * 旧数据（conversation_id 为 NULL）在首次访问时补绑定到当前会话。
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
    public BriefingVO getTodayBriefing(Long userId, Long conversationId) {
        LocalDate today = LocalDate.now();
        // 1) 目标归属（会话×日期）已有记录 → 直接读缓存
        DailyBriefing row = findRow(userId, conversationId, today);
        if (row != null) {
            return toVO(row);
        }
        // 2) 存在未绑定的当日旧数据 → 补绑定到当前会话
        DailyBriefing unbound = findUnboundRow(userId, today);
        if (unbound != null) {
            if (conversationId != null) {
                unbound.setConversationId(conversationId);
                unbound.setUpdatedAt(LocalDateTime.now());
                dailyBriefingMapper.updateById(unbound);
                log.info("旧简报已补绑定会话, briefingId={}, conversationId={}", unbound.getId(), conversationId);
            }
            return toVO(unbound);
        }
        // 3) 未指定会话（如新对话尚未创建）→ 当日已有任意归属的简报则复用，避免重复生成
        if (conversationId == null) {
            DailyBriefing any = findLatestRow(userId, today);
            if (any != null) {
                return toVO(any);
            }
        }
        return generateAndSave(userId, conversationId, today);
    }

    @Override
    public BriefingVO refreshTodayBriefing(Long userId, Long conversationId) {
        LocalDate today = LocalDate.now();
        Map<String, Object> stats = learningStatsService.getStatsSnapshot(userId);
        String statsJson = JSONUtil.toJsonStr(stats);
        String content = generateContent(userId, today, stats);

        DailyBriefing row = findRow(userId, conversationId, today);
        if (row == null && conversationId != null) {
            // 目标会话当日无记录：优先绑定未绑定旧数据
            row = findUnboundRow(userId, today);
        }
        if (row == null) {
            row = new DailyBriefing()
                    .setUserId(userId)
                    .setConversationId(conversationId)
                    .setBriefDate(today);
        } else if (row.getConversationId() == null && conversationId != null) {
            row.setConversationId(conversationId);
        }
        row.setStatsJson(statsJson).setContent(content).setUpdatedAt(LocalDateTime.now());
        if (row.getId() == null) {
            row.setCreatedAt(LocalDateTime.now());
            dailyBriefingMapper.insert(row);
        } else {
            dailyBriefingMapper.updateById(row);
        }
        log.info("每日简报已刷新, userId={}, conversationId={}, date={}, 字数={}", userId, conversationId, today, content.length());
        return toVO(row);
    }

    @Override
    public List<BriefingVO> listByConversation(Long userId, Long conversationId) {
        return dailyBriefingMapper.selectList(new QueryWrapper<DailyBriefing>()
                        .eq("user_id", userId)
                        .eq("conversation_id", conversationId)
                        .orderByAsc("created_at"))
                .stream()
                .map(this::toVO)
                .toList();
    }

    @Override
    public Map<String, Object> getStatsSnapshot(Long userId) {
        return learningStatsService.getStatsSnapshot(userId);
    }

    /**
     * 生成并保存：同日已有任意归属的简报则复用其内容与统计快照（避免重复 LLM 调用），仅新落一条归属记录
     */
    private BriefingVO generateAndSave(Long userId, Long conversationId, LocalDate today) {
        DailyBriefing source = findLatestRow(userId, today);
        String statsJson;
        String content;
        if (source != null && source.getContent() != null && !source.getContent().isBlank()) {
            statsJson = source.getStatsJson();
            content = source.getContent();
        } else {
            Map<String, Object> stats = learningStatsService.getStatsSnapshot(userId);
            statsJson = JSONUtil.toJsonStr(stats);
            content = generateContent(userId, today, stats);
        }

        DailyBriefing row = new DailyBriefing()
                .setUserId(userId)
                .setConversationId(conversationId)
                .setBriefDate(today)
                .setStatsJson(statsJson)
                .setContent(content)
                .setCreatedAt(LocalDateTime.now())
                .setUpdatedAt(LocalDateTime.now());
        dailyBriefingMapper.insert(row);
        log.info("每日简报已生成, userId={}, conversationId={}, date={}, 字数={}", userId, conversationId, today, content.length());
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

    /** 按（用户 × 会话 × 日期）查询；conversationId 为空时查询未绑定旧数据 */
    private DailyBriefing findRow(Long userId, Long conversationId, LocalDate today) {
        QueryWrapper<DailyBriefing> qw = new QueryWrapper<DailyBriefing>()
                .eq("user_id", userId)
                .eq("brief_date", today);
        if (conversationId == null) {
            qw.isNull("conversation_id");
        } else {
            qw.eq("conversation_id", conversationId);
        }
        return dailyBriefingMapper.selectOne(qw);
    }

    private DailyBriefing findUnboundRow(Long userId, LocalDate today) {
        return dailyBriefingMapper.selectOne(new QueryWrapper<DailyBriefing>()
                .eq("user_id", userId)
                .eq("brief_date", today)
                .isNull("conversation_id")
                .last("LIMIT 1"));
    }

    private DailyBriefing findLatestRow(Long userId, LocalDate today) {
        List<DailyBriefing> rows = dailyBriefingMapper.selectList(new QueryWrapper<DailyBriefing>()
                .eq("user_id", userId)
                .eq("brief_date", today)
                .orderByDesc("id")
                .last("LIMIT 1"));
        return rows.isEmpty() ? null : rows.get(0);
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
                .setConversationId(row.getConversationId())
                .setBriefDate(row.getBriefDate().toString())
                .setContent(row.getContent())
                .setStats(stats)
                .setGeneratedAt(row.getUpdatedAt());
    }
}
