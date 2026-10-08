package com.xueji.agent.service;

import com.xueji.agent.domain.entity.DailyBriefing;
import com.xueji.agent.domain.vo.BriefingVO;
import com.xueji.agent.config.ChatClientFactory;
import com.xueji.agent.mapper.DailyBriefingMapper;
import com.xueji.agent.service.impl.BriefingServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
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
 * 每日简报服务：按（用户 × 会话 × 日期）惰性生成（幂等缓存）、未绑定旧数据补绑定、
 * 同日跨会话内容复用（不重复调 LLM）、强制刷新、按会话查询、用户消息组装。
 * 统计聚合口径见 LearningStatsServiceImplTest
 */
class BriefingServiceImplTest {

    private static final Long USER_ID = 5L;
    private static final Long CONV_A = 101L;
    private static final Long CONV_B = 102L;
    private static final String BRIEFING_TEXT = "本周你新增了 1 篇笔记，复习了 8 张卡。薄弱点集中在极限计算……建议明天先刷待复习的 3 张卡。";

    private DailyBriefingMapper dailyBriefingMapper;
    private LearningStatsService learningStatsService;
    private ChatClient chatClient;
    private AiModelService aiModelService;
    private BriefingServiceImpl service;

    @BeforeEach
    void setUp() {
        dailyBriefingMapper = mock(DailyBriefingMapper.class);
        learningStatsService = mock(LearningStatsService.class);
        chatClient = mock(ChatClient.class, RETURNS_DEEP_STUBS);
        aiModelService = mock(AiModelService.class);
        service = new BriefingServiceImpl();
        ReflectionTestUtils.setField(service, "dailyBriefingMapper", dailyBriefingMapper);
        ReflectionTestUtils.setField(service, "learningStatsService", learningStatsService);
        ReflectionTestUtils.setField(service, "aiModelService", aiModelService);
        when(aiModelService.resolve(USER_ID, AiModelService.MODULE_BRIEFING, ChatClientFactory.Variant.GENERATION))
                .thenReturn(chatClient);

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
        stats.put("weakCards", List.of());
        return stats;
    }

    private DailyBriefing row(Long conversationId, String content) {
        return new DailyBriefing().setId(9L).setUserId(USER_ID).setConversationId(conversationId)
                .setBriefDate(LocalDate.now()).setStatsJson("{\"dueToday\":3}").setContent(content)
                .setCreatedAt(LocalDateTime.now()).setUpdatedAt(LocalDateTime.now());
    }

    @Test
    void getTodayBriefing_targetRowExists_readsCacheWithoutInsert() {
        DailyBriefing cached = row(CONV_A, BRIEFING_TEXT);
        // findRow 命中
        when(dailyBriefingMapper.selectOne(any())).thenReturn(cached);

        var first = service.getTodayBriefing(USER_ID, CONV_A);
        var second = service.getTodayBriefing(USER_ID, CONV_A);

        assertEquals(BRIEFING_TEXT, first.getContent());
        assertEquals(CONV_A, first.getConversationId());
        assertThat(first.getStats()).containsEntry("dueToday", 3);
        // 幂等：当天同会话再次访问读缓存，不落库不触发 LLM
        verify(dailyBriefingMapper, never()).insert(any(DailyBriefing.class));
        verify(dailyBriefingMapper, never()).updateById(any(DailyBriefing.class));
        verify(learningStatsService, never()).getStatsSnapshot(any());
        assertEquals(first.getContent(), second.getContent());
    }

    @Test
    void getTodayBriefing_noCache_generatesAndBindsConversation() {
        when(learningStatsService.getStatsSnapshot(USER_ID)).thenReturn(snapshot());
        // findRow 未命中、未绑定旧数据未命中
        when(dailyBriefingMapper.selectOne(any())).thenReturn(null, null);
        when(dailyBriefingMapper.selectList(any())).thenReturn(List.of());
        when(dailyBriefingMapper.insert(any(DailyBriefing.class))).thenReturn(1);

        var vo = service.getTodayBriefing(USER_ID, CONV_A);

        assertEquals(BRIEFING_TEXT, vo.getContent());
        var inserted = dailyBriefingInsert();
        assertEquals(CONV_A, inserted.getConversationId());
        assertEquals(LocalDate.now(), inserted.getBriefDate());
    }

    @Test
    void getTodayBriefing_unboundLegacyRow_bindsToCurrentConversation() {
        DailyBriefing legacy = row(null, BRIEFING_TEXT);
        // findRow 未命中 → findUnboundRow 命中旧数据
        when(dailyBriefingMapper.selectOne(any())).thenReturn(null, legacy);
        when(dailyBriefingMapper.updateById(any(DailyBriefing.class))).thenReturn(1);

        var vo = service.getTodayBriefing(USER_ID, CONV_A);

        assertEquals(BRIEFING_TEXT, vo.getContent());
        assertEquals(CONV_A, legacy.getConversationId());
        verify(dailyBriefingMapper).updateById(legacy);
        // 补绑定不重新生成：不落新行、不触发 LLM
        verify(dailyBriefingMapper, never()).insert(any(DailyBriefing.class));
        verify(learningStatsService, never()).getStatsSnapshot(any());
    }

    @Test
    void getTodayBriefing_withoutConversation_reusesAnyRowToday() {
        DailyBriefing bound = row(CONV_A, BRIEFING_TEXT);
        // findRow(isNull) 未命中 → findUnboundRow 未命中 → findLatestRow 命中已绑定行
        when(dailyBriefingMapper.selectOne(any())).thenReturn(null, null);
        when(dailyBriefingMapper.selectList(any())).thenReturn(List.of(bound));

        var vo = service.getTodayBriefing(USER_ID, null);

        // 新对话尚未创建：复用当日已有简报，不生成新归属行
        assertEquals(BRIEFING_TEXT, vo.getContent());
        verify(dailyBriefingMapper, never()).insert(any(DailyBriefing.class));
        verify(dailyBriefingMapper, never()).updateById(any(DailyBriefing.class));
        verify(learningStatsService, never()).getStatsSnapshot(any());
    }

    @Test
    void getTodayBriefing_sameDayOtherConversation_reusesContentWithoutLlm() {
        DailyBriefing boundA = row(CONV_A, BRIEFING_TEXT);
        // findRow(CONV_B) 未命中 → findUnboundRow 未命中 → generateAndSave 里 findLatestRow 命中 CONV_A 的行
        when(dailyBriefingMapper.selectOne(any())).thenReturn(null, null);
        when(dailyBriefingMapper.selectList(any())).thenReturn(List.of(boundA));
        when(dailyBriefingMapper.insert(any(DailyBriefing.class))).thenReturn(1);

        var vo = service.getTodayBriefing(USER_ID, CONV_B);

        // 同日另一会话：复用内容落新归属行，不重复调用 LLM（不聚合统计）
        assertEquals(BRIEFING_TEXT, vo.getContent());
        var inserted = dailyBriefingInsert();
        assertEquals(CONV_B, inserted.getConversationId());
        assertEquals(boundA.getContent(), inserted.getContent());
        verify(learningStatsService, never()).getStatsSnapshot(any());
    }

    @Test
    void refreshShouldRegenerateEvenWhenCached() {
        DailyBriefing cached = row(CONV_A, "旧简报");
        when(learningStatsService.getStatsSnapshot(USER_ID)).thenReturn(snapshot());
        when(dailyBriefingMapper.selectOne(any())).thenReturn(cached);
        when(dailyBriefingMapper.updateById(any(DailyBriefing.class))).thenReturn(1);

        var refreshed = service.refreshTodayBriefing(USER_ID, CONV_A);

        assertEquals(BRIEFING_TEXT, refreshed.getContent());
        verify(dailyBriefingMapper).updateById(cached);
        verify(dailyBriefingMapper, never()).insert(any(DailyBriefing.class));
    }

    @Test
    void refreshTargetMissing_bindsUnboundRowInsteadOfInsert() {
        DailyBriefing legacy = row(null, "旧简报");
        when(learningStatsService.getStatsSnapshot(USER_ID)).thenReturn(snapshot());
        // findRow(CONV_A) 未命中 → findUnboundRow 命中
        when(dailyBriefingMapper.selectOne(any())).thenReturn(null, legacy);
        when(dailyBriefingMapper.updateById(any(DailyBriefing.class))).thenReturn(1);

        service.refreshTodayBriefing(USER_ID, CONV_A);

        assertEquals(CONV_A, legacy.getConversationId());
        assertEquals(BRIEFING_TEXT, legacy.getContent());
        verify(dailyBriefingMapper).updateById(legacy);
        verify(dailyBriefingMapper, never()).insert(any(DailyBriefing.class));
    }

    @Test
    void listByConversation_returnsVosOfThatConversation() {
        DailyBriefing day1 = new DailyBriefing().setId(1L).setUserId(USER_ID).setConversationId(CONV_A)
                .setBriefDate(LocalDate.now().minusDays(1)).setStatsJson("{}").setContent("昨日简报")
                .setCreatedAt(LocalDateTime.now().minusDays(1)).setUpdatedAt(LocalDateTime.now().minusDays(1));
        DailyBriefing day2 = row(CONV_A, BRIEFING_TEXT);
        when(dailyBriefingMapper.selectList(any())).thenReturn(List.of(day1, day2));

        List<BriefingVO> list = service.listByConversation(USER_ID, CONV_A);

        assertEquals(2, list.size());
        assertEquals("昨日简报", list.get(0).getContent());
        assertEquals(BRIEFING_TEXT, list.get(1).getContent());
        assertEquals(CONV_A, list.get(0).getConversationId());
        assertEquals(CONV_A, list.get(1).getConversationId());
        // 每条简报带生成时间，前端据此插到最后一条更早消息之后
        assertThat(list.get(0).getGeneratedAt()).isNotNull();
    }

    @Test
    void getTodayBriefing_generatedAtUsesCreatedAt_notUpdatedAt() {
        // 落位时间必须是真实生成时刻（createdAt）：updatedAt 会随补绑定/刷新内容变化，导致简报在会话中跳位（B27）
        LocalDateTime generated = LocalDateTime.now().minusHours(3);
        DailyBriefing cached = row(CONV_A, BRIEFING_TEXT)
                .setCreatedAt(generated)
                .setUpdatedAt(LocalDateTime.now());
        when(dailyBriefingMapper.selectOne(any())).thenReturn(cached);

        var vo = service.getTodayBriefing(USER_ID, CONV_A);

        assertThat(vo.getGeneratedAt()).isEqualTo(generated);
    }

    @Test
    void buildUserContent_shouldEmbedStatsJson() {
        String content = BriefingServiceImpl.buildUserContent(Map.of("dueToday", 3L, "weakCards", List.of()));

        assertThat(content).startsWith("以下是用户本周的学习统计数据");
        assertThat(content).contains("dueToday").contains("weakCards");
    }

    /** 捕获 insert 落库的实体（ArgumentCaptor 断言字段） */
    private DailyBriefing dailyBriefingInsert() {
        var captor = org.mockito.ArgumentCaptor.forClass(DailyBriefing.class);
        verify(dailyBriefingMapper, times(1)).insert(captor.capture());
        return captor.getValue();
    }
}
