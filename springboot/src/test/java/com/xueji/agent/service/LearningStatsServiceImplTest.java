package com.xueji.agent.service;

import com.xueji.agent.domain.entity.Note;
import com.xueji.agent.domain.entity.QuestionRecord;
import com.xueji.agent.domain.entity.ReviewCard;
import com.xueji.agent.domain.entity.ReviewLog;
import com.xueji.agent.mapper.CourseMapper;
import com.xueji.agent.mapper.NoteMapper;
import com.xueji.agent.mapper.QuestionRecordMapper;
import com.xueji.agent.mapper.ReviewCardMapper;
import com.xueji.agent.mapper.ReviewLogMapper;
import com.xueji.agent.mapper.SimilarQuestionMapper;
import com.xueji.agent.domain.dto.WeakCardInfo;
import com.xueji.agent.service.impl.LearningStatsServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 学习状态统计：计数口径与薄弱卡聚合（来源已删剔除、上限截断）
 */
class LearningStatsServiceImplTest {

    private ReviewCardMapper reviewCardMapper;
    private ReviewLogMapper reviewLogMapper;
    private NoteMapper noteMapper;
    private CourseMapper courseMapper;
    private QuestionRecordMapper questionRecordMapper;
    private SimilarQuestionMapper similarQuestionMapper;
    private LearningStatsServiceImpl service;

    private static final Long USER_ID = 5L;

    @BeforeEach
    void setUp() {
        reviewCardMapper = mock(ReviewCardMapper.class);
        reviewLogMapper = mock(ReviewLogMapper.class);
        noteMapper = mock(NoteMapper.class);
        courseMapper = mock(CourseMapper.class);
        questionRecordMapper = mock(QuestionRecordMapper.class);
        similarQuestionMapper = mock(SimilarQuestionMapper.class);
        service = new LearningStatsServiceImpl();
        ReflectionTestUtils.setField(service, "reviewCardMapper", reviewCardMapper);
        ReflectionTestUtils.setField(service, "reviewLogMapper", reviewLogMapper);
        ReflectionTestUtils.setField(service, "noteMapper", noteMapper);
        ReflectionTestUtils.setField(service, "courseMapper", courseMapper);
        ReflectionTestUtils.setField(service, "questionRecordMapper", questionRecordMapper);
        ReflectionTestUtils.setField(service, "similarQuestionMapper", similarQuestionMapper);
    }

    private ReviewLog againLog(long cardId) {
        return new ReviewLog().setId(cardId).setUserId(USER_ID).setCardId(cardId)
                .setGrade(ReviewScheduler.GRADE_AGAIN).setReviewedAt(LocalDateTime.now());
    }

    private ReviewCard questionCard(long cardId, long questionId) {
        return new ReviewCard().setId(cardId).setUserId(USER_ID).setCardType("question")
                .setRefId(questionId).setDeleted(0);
    }

    @Test
    void snapshot_shouldAggregateCounters() {
        // reviewCardMapper.selectCount 依次为 dueToday / totalCards
        when(reviewCardMapper.selectCount(any())).thenReturn(3L, 10L);
        // reviewLogMapper.selectCount 依次为 reviewedThisWeek / againThisWeek
        when(reviewLogMapper.selectCount(any())).thenReturn(8L, 2L);
        when(noteMapper.selectCount(any())).thenReturn(5L);
        when(courseMapper.selectCount(any())).thenReturn(4L, 3L);
        when(reviewLogMapper.selectList(any())).thenReturn(List.of());

        Map<String, Object> stats = service.getStatsSnapshot(USER_ID);

        assertEquals(3L, stats.get("dueToday"));
        assertEquals(10L, stats.get("totalCards"));
        assertEquals(8L, stats.get("reviewedThisWeek"));
        assertEquals(2L, stats.get("againThisWeek"));
        assertEquals(5L, stats.get("notesCreatedThisWeek"));
        assertEquals(4L, stats.get("coursesTotal"));
        assertEquals(3L, stats.get("coursesSuccess"));
        assertThat((List<?>) stats.get("weakCards")).isEmpty();
    }

    @Test
    void weakCards_shouldDedupeByCardAndLoadSource() {
        // 同一张卡本周生疏两次 → 去重为一张
        when(reviewLogMapper.selectList(any())).thenReturn(List.of(againLog(1L), againLog(1L)));
        when(reviewCardMapper.selectById(1L)).thenReturn(questionCard(1L, 7L));
        when(questionRecordMapper.selectById(7L)).thenReturn(
                new QuestionRecord().setId(7L).setUserId(USER_ID).setDeleted(0)
                        .setQuestionText("求极限").setAnalysis("概念混淆").setSubject("数学"));

        Map<String, Object> stats = service.getStatsSnapshot(USER_ID);

        assertThat((List<?>) stats.get("weakCards")).hasSize(1);
        WeakCardInfo weak = (WeakCardInfo) ((List<?>) stats.get("weakCards")).get(0);
        assertThat(weak.getFront()).isEqualTo("求极限");
        assertThat(weak.getAnalysis()).isEqualTo("概念混淆");
        assertThat(weak.getSubject()).isEqualTo("数学");
    }

    @Test
    void weakCards_shouldExcludeDeletedSources() {
        when(reviewLogMapper.selectList(any())).thenReturn(List.of(againLog(1L)));
        when(reviewCardMapper.selectById(1L)).thenReturn(questionCard(1L, 7L));
        when(questionRecordMapper.selectById(7L)).thenReturn(
                new QuestionRecord().setId(7L).setUserId(USER_ID).setDeleted(1));

        Map<String, Object> stats = service.getStatsSnapshot(USER_ID);

        assertThat((List<?>) stats.get("weakCards")).isEmpty();
    }

    @Test
    void weakCards_shouldCapAtFive() {
        // 6 张不同的生疏卡 → 只保留最近 5 张
        List<ReviewLog> logs = new java.util.ArrayList<>();
        for (long cardId = 1; cardId <= 6; cardId++) {
            logs.add(againLog(cardId));
            when(reviewCardMapper.selectById(cardId)).thenReturn(questionCard(cardId, cardId));
            when(questionRecordMapper.selectById(cardId)).thenReturn(
                    new QuestionRecord().setId(cardId).setUserId(USER_ID).setDeleted(0).setQuestionText("题" + cardId));
        }
        when(reviewLogMapper.selectList(any())).thenReturn(logs);

        Map<String, Object> stats = service.getStatsSnapshot(USER_ID);

        assertThat((List<?>) stats.get("weakCards")).hasSize(5);
    }
}
