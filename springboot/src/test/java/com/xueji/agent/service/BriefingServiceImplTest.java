package com.xueji.agent.service;

import com.xueji.agent.domain.entity.DailyBriefing;
import com.xueji.agent.mapper.DailyBriefingMapper;
import com.xueji.agent.service.impl.BriefingServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 每日简报服务：惰性生成（当天缓存幂等）、强制刷新、用户消息组装。
 * 统计聚合口径见 LearningStatsServiceImplTest
 */
class BriefingServiceImplTest {

    private static final Long USER_ID = 5L;
    private static final String BRIEFING_TEXT = "本周你新增了 1 篇笔记，复习了 8 张卡。薄弱点集中在极限计算……建议明天先刷待复习的 3 张卡。";

    private DailyBriefingMapper dailyBriefingMapper;
    private LearningStatsService learningStatsService;
    private ChatClient chatClient;
    private BriefingServiceImpl service;

    @BeforeEach
    void setUp() {
        dailyBriefingMapper = mock(DailyBriefingMapper.class);
        learningStatsService = mock(LearningStatsService.class);
        chatClient = mock(ChatClient.class, RETURNS_DEEP_STUBS);
        service = new BriefingServiceImpl();
        ReflectionTestUtils.setField(service, "dailyBriefingMapper", dailyBriefingMapper);
        ReflectionTestUtils.setField(service, "learningStatsService", learningStatsService);
        ReflectionTestUtils.setField(service, "chatClient", chatClient);

        when(chatClient.prompt()
                .system(anyString())
                .user(anyString())
                .call()
                .content()).thenReturn(BRIEFING_TEXT);
    }

    private Map<String, Object> snapshot() {
        Map<String, Object> stats = new HashMap<>();
        stats.put("dueToday", 3L);
        stats.put("totalCards", 10L);
        stats.put("weakCards", java.util.List.of());
        return stats;
    }

    @Test
    void getTodayBriefing_shouldGenerateInsertAndCache() {
        when(learningStatsService.getStatsSnapshot(USER_ID)).thenReturn(snapshot());
        DailyBriefing cached = new DailyBriefing().setId(9L).setUserId(USER_ID)
                .setBriefDate(LocalDate.now()).setContent(BRIEFING_TEXT)
                .setCreatedAt(LocalDateTime.now()).setUpdatedAt(LocalDateTime.now());
        // 真实调用序列：首次访问查 2 次（入口 + 生成前）均为无缓存，第二次访问读到已落库简报
        when(dailyBriefingMapper.selectOne(any())).thenReturn(null, null, cached);
        when(dailyBriefingMapper.insert(any(DailyBriefing.class))).thenReturn(1);

        var first = service.getTodayBriefing(USER_ID);
        var second = service.getTodayBriefing(USER_ID);

        assertEquals(BRIEFING_TEXT, first.getContent());
        assertThat(first.getStats()).containsEntry("dueToday", 3);
        // 惰性生成幂等：当天第二次访问读缓存，不再触发 LLM
        verify(dailyBriefingMapper, times(1)).insert(any(DailyBriefing.class));
        assertEquals(first.getContent(), second.getContent());
    }

    @Test
    void refreshShouldRegenerateEvenWhenCached() {
        DailyBriefing cached = new DailyBriefing().setId(9L).setUserId(USER_ID)
                .setBriefDate(LocalDate.now()).setContent("旧简报")
                .setCreatedAt(LocalDateTime.now()).setUpdatedAt(LocalDateTime.now());
        when(learningStatsService.getStatsSnapshot(USER_ID)).thenReturn(snapshot());
        when(dailyBriefingMapper.selectOne(any())).thenReturn(cached);
        when(dailyBriefingMapper.updateById(any(DailyBriefing.class))).thenReturn(1);

        var refreshed = service.refreshTodayBriefing(USER_ID);

        assertEquals(BRIEFING_TEXT, refreshed.getContent());
        verify(dailyBriefingMapper).updateById(cached);
        verify(dailyBriefingMapper, never()).insert(any(DailyBriefing.class));
    }

    @Test
    void buildUserContent_shouldEmbedStatsJson() {
        String content = BriefingServiceImpl.buildUserContent(Map.of("dueToday", 3L, "weakCards", java.util.List.of()));

        assertThat(content).startsWith("以下是用户本周的学习统计数据");
        assertThat(content).contains("dueToday").contains("weakCards");
    }
}
