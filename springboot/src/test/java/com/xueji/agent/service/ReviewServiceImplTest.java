package com.xueji.agent.service;

import com.xueji.agent.domain.entity.Note;
import com.xueji.agent.domain.entity.QuestionRecord;
import com.xueji.agent.domain.entity.ReviewCard;
import com.xueji.agent.domain.entity.ReviewLog;
import com.xueji.agent.domain.entity.SimilarQuestion;
import com.xueji.agent.domain.vo.ReviewCardVO;
import com.xueji.agent.exception.BusinessException;
import com.xueji.agent.mapper.NoteMapper;
import com.xueji.agent.mapper.QuestionRecordMapper;
import com.xueji.agent.mapper.ReviewCardMapper;
import com.xueji.agent.mapper.ReviewLogMapper;
import com.xueji.agent.mapper.SimilarQuestionMapper;
import com.xueji.agent.service.impl.ReviewServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 复习系统服务：加卡（去重 / 来源校验 / 恢复已移出）、今日队列组装、评分调度与流水、统计、级联移除
 */
class ReviewServiceImplTest {

    private ReviewCardMapper reviewCardMapper;
    private ReviewLogMapper reviewLogMapper;
    private QuestionRecordMapper questionRecordMapper;
    private SimilarQuestionMapper similarQuestionMapper;
    private NoteMapper noteMapper;
    private ReviewServiceImpl service;

    private static final Long USER_ID = 5L;

    @BeforeEach
    void setUp() {
        reviewCardMapper = mock(ReviewCardMapper.class);
        reviewLogMapper = mock(ReviewLogMapper.class);
        questionRecordMapper = mock(QuestionRecordMapper.class);
        similarQuestionMapper = mock(SimilarQuestionMapper.class);
        noteMapper = mock(NoteMapper.class);
        service = new ReviewServiceImpl();
        ReflectionTestUtils.setField(service, "reviewCardMapper", reviewCardMapper);
        ReflectionTestUtils.setField(service, "reviewLogMapper", reviewLogMapper);
        ReflectionTestUtils.setField(service, "questionRecordMapper", questionRecordMapper);
        ReflectionTestUtils.setField(service, "similarQuestionMapper", similarQuestionMapper);
        ReflectionTestUtils.setField(service, "noteMapper", noteMapper);
    }

    private ReviewCard card(Long id, String type, Long refId) {
        return new ReviewCard().setId(id).setUserId(USER_ID).setCardType(type).setRefId(refId)
                .setDueAt(LocalDateTime.now()).setIntervalDays(0)
                .setEase(new BigDecimal("2.50")).setReps(0).setLapses(0).setDeleted(0);
    }

    // ---- 加卡 ----

    @Test
    void addCard_questionShouldInsertWithDueNow() {
        when(questionRecordMapper.selectById(7L)).thenReturn(
                new QuestionRecord().setId(7L).setUserId(USER_ID).setDeleted(0).setQuestionText("题"));
        when(reviewCardMapper.selectOne(any())).thenReturn(null);
        when(reviewCardMapper.insert(any(ReviewCard.class))).thenReturn(1);

        ReviewCardVO vo = service.addCard(USER_ID, "question", 7L);

        ArgumentCaptor<ReviewCard> captor = ArgumentCaptor.forClass(ReviewCard.class);
        verify(reviewCardMapper).insert(captor.capture());
        ReviewCard card = captor.getValue();
        assertEquals(USER_ID, card.getUserId());
        assertEquals("question", card.getCardType());
        assertEquals(0, card.getIntervalDays());
        // 加入即可复习
        assertThat(card.getDueAt()).isBeforeOrEqualTo(LocalDateTime.now().plusSeconds(1));
        assertEquals(vo.getRefId(), card.getRefId());
    }

    @Test
    void addCard_duplicateShouldReject() {
        when(reviewCardMapper.selectOne(any())).thenReturn(card(1L, "question", 7L));

        assertThrows(BusinessException.class, () -> service.addCard(USER_ID, "question", 7L));
        verify(reviewCardMapper, never()).insert(any(ReviewCard.class));
    }

    @Test
    void addCard_removedCardShouldReviveWithResetState() {
        when(questionRecordMapper.selectById(7L)).thenReturn(
                new QuestionRecord().setId(7L).setUserId(USER_ID).setDeleted(0).setQuestionText("题"));
        ReviewCard removed = card(1L, "question", 7L).setDeleted(1).setIntervalDays(30).setReps(9);
        when(reviewCardMapper.selectOne(any())).thenReturn(removed);
        when(reviewCardMapper.updateById(any(ReviewCard.class))).thenReturn(1);

        ReviewCardVO vo = service.addCard(USER_ID, "question", 7L);

        assertEquals(0, removed.getDeleted());
        assertEquals(0, removed.getIntervalDays());
        assertEquals(0, removed.getReps());
        assertEquals(vo.getId(), removed.getId());
        verify(reviewCardMapper, never()).insert(any(ReviewCard.class));
    }

    @Test
    void addCard_noteGroupShouldReject() {
        when(noteMapper.selectById(11L)).thenReturn(
                new Note().setId(11L).setUserId(USER_ID).setDeleted(0).setNodeType(1).setTitle("分组"));

        assertThrows(BusinessException.class, () -> service.addCard(USER_ID, "note", 11L));
    }

    @Test
    void addCard_illegalTypeShouldReject() {
        assertThrows(BusinessException.class, () -> service.addCard(USER_ID, "video", 1L));
    }

    // ---- 今日队列 ----

    @Test
    void todayQueue_shouldAssembleAllTypesAndSkipDeletedSources() {
        ReviewCard q = card(1L, "question", 7L);
        ReviewCard n = card(2L, "note", 11L);
        ReviewCard s = card(3L, "similar", 21L);
        when(reviewCardMapper.selectList(any())).thenReturn(List.of(q, n, s));
        when(questionRecordMapper.selectById(7L)).thenReturn(
                new QuestionRecord().setId(7L).setUserId(USER_ID).setDeleted(0)
                        .setQuestionText("题干").setCorrectAnswer("解答").setAnalysis("错因").setImageOssKey("oss://a.png"));
        when(noteMapper.selectById(11L)).thenReturn(
                new Note().setId(11L).setUserId(USER_ID).setDeleted(0).setNodeType(0).setTitle("罗尔定理").setContent("正文"));
        // 相似题已删除 → 该卡跳过不出现在队列
        when(similarQuestionMapper.selectById(21L)).thenReturn(
                new SimilarQuestion().setId(21L).setUserId(USER_ID).setDeleted(1));

        List<ReviewCardVO> queue = service.todayQueue(USER_ID);

        assertThat(queue).hasSize(2);
        assertThat(queue.get(0).getFrontText()).isEqualTo("题干");
        assertThat(queue.get(0).getBackText()).isEqualTo("解答");
        assertThat(queue.get(0).getAnalysis()).isEqualTo("错因");
        assertThat(queue.get(0).getImageUrl()).isEqualTo("oss://a.png");
        assertThat(queue.get(1).getFrontText()).isEqualTo("罗尔定理");
        assertThat(queue.get(1).getBackText()).isEqualTo("正文");
    }

    // ---- 评分 ----

    @Test
    void review_shouldUpdateStateAndWriteLog() {
        ReviewCard card = card(1L, "question", 7L);
        when(reviewCardMapper.selectById(1L)).thenReturn(card);
        when(reviewCardMapper.updateById(any(ReviewCard.class))).thenReturn(1);
        when(reviewLogMapper.insert(any(ReviewLog.class))).thenReturn(1);

        ReviewCardVO vo = service.review(USER_ID, 1L, ReviewScheduler.GRADE_GOOD);

        // 首次熟练：间隔 = round(1 * 2.5) = 3 天，dueAt 后移
        assertEquals(3, vo.getIntervalDays());
        assertEquals(1, vo.getReps());
        assertThat(vo.getDueAt()).isAfter(LocalDateTime.now());
        ArgumentCaptor<ReviewLog> captor = ArgumentCaptor.forClass(ReviewLog.class);
        verify(reviewLogMapper).insert(captor.capture());
        assertEquals(ReviewScheduler.GRADE_GOOD, captor.getValue().getGrade());
        assertEquals(3, captor.getValue().getIntervalAfter());
    }

    @Test
    void review_illegalGradeShouldReject() {
        when(reviewCardMapper.selectById(1L)).thenReturn(card(1L, "question", 7L));

        assertThrows(BusinessException.class, () -> service.review(USER_ID, 1L, 9));
    }

    // ---- 统计 ----

    @Test
    void stats_shouldReturnDueTotalReviewed() {
        when(reviewCardMapper.selectCount(any())).thenReturn(3L, 10L);
        when(reviewLogMapper.selectCount(any())).thenReturn(2L);

        Map<String, Object> stats = service.stats(USER_ID);

        assertEquals(3L, stats.get("dueCount"));
        assertEquals(10L, stats.get("total"));
        assertEquals(2L, stats.get("reviewedToday"));
    }

    // ---- 移除与级联 ----

    @Test
    void removeCard_shouldSoftDelete() {
        ReviewCard card = card(1L, "note", 11L);
        when(reviewCardMapper.selectById(1L)).thenReturn(card);
        when(reviewCardMapper.updateById(any(ReviewCard.class))).thenReturn(1);

        service.removeCard(USER_ID, 1L);

        assertEquals(1, card.getDeleted());
    }

    @Test
    void removeBySource_shouldSoftDeleteMatchingCard() {
        service.removeBySource(USER_ID, "question", 7L);

        verify(reviewCardMapper).update(any(), any());
    }
}
