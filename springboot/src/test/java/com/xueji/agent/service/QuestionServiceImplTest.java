package com.xueji.agent.service;

import com.xueji.agent.ai.RagIngestService;
import com.xueji.agent.domain.dto.QuestionUpdateDto;
import com.xueji.agent.domain.entity.Conversation;
import com.xueji.agent.domain.entity.Message;
import com.xueji.agent.domain.entity.QuestionRecord;
import com.xueji.agent.domain.entity.SimilarQuestion;
import com.xueji.agent.domain.vo.PageVO;
import com.xueji.agent.domain.vo.QuestionItemVO;
import com.xueji.agent.exception.BusinessException;
import com.xueji.agent.mapper.ConversationMapper;
import com.xueji.agent.mapper.MessageMapper;
import com.xueji.agent.mapper.QuestionRecordMapper;
import com.xueji.agent.mapper.SimilarQuestionMapper;
import com.xueji.agent.service.impl.QuestionServiceImpl;
import com.xueji.agent.service.impl.QuestionVectorStoreService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 题目记录服务测试：拍照题目（question_record）与 AI 相似题（similar_question）双表存储分派、
 * 合并分页列表、详情、编辑、删除
 */
class QuestionServiceImplTest {

    private QuestionRecordMapper questionRecordMapper;
    private SimilarQuestionMapper similarQuestionMapper;
    private ConversationMapper conversationMapper;
    private MessageMapper messageMapper;
    private QuestionVectorStoreService questionVectorStoreService;
    private RagIngestService ragIngestService;
    private QuestionServiceImpl service;

    @BeforeEach
    void setUp() {
        questionRecordMapper = mock(QuestionRecordMapper.class);
        similarQuestionMapper = mock(SimilarQuestionMapper.class);
        conversationMapper = mock(ConversationMapper.class);
        messageMapper = mock(MessageMapper.class);
        questionVectorStoreService = mock(QuestionVectorStoreService.class);
        ragIngestService = mock(RagIngestService.class);
        service = new QuestionServiceImpl();
        ReflectionTestUtils.setField(service, "questionRecordMapper", questionRecordMapper);
        ReflectionTestUtils.setField(service, "similarQuestionMapper", similarQuestionMapper);
        ReflectionTestUtils.setField(service, "conversationMapper", conversationMapper);
        ReflectionTestUtils.setField(service, "messageMapper", messageMapper);
        ReflectionTestUtils.setField(service, "questionVectorStoreService", questionVectorStoreService);
        ReflectionTestUtils.setField(service, "ragIngestService", ragIngestService);
    }

    private Conversation conversationOf(Long userId) {
        return new Conversation().setId(100L).setUserId(userId);
    }

    private Message questionMessageOf(Long id, String payload) {
        Message message = new Message()
                .setConversationId(100L)
                .setRole("user")
                .setContent("请看这张图片")
                .setPayload(payload);
        message.setId(id);
        return message;
    }

    // ---- 拍照题目（photo → question_record）----

    @Test
    void saveFromConversation_photo_success() {
        when(conversationMapper.selectById(100L)).thenReturn(conversationOf(1L));
        when(messageMapper.selectList(any())).thenReturn(List.of(
                questionMessageOf(10L, "{\"imageUrl\":\"https://oss.example.com/chat/a.png\"}")));
        when(questionRecordMapper.insert(any(QuestionRecord.class))).thenReturn(1);

        boolean saved = service.saveFromConversation(1L, 100L, "photo", null,
                "已知函数 f(x)=ln(x+1)-x", "极大值为 0", null, "数学");

        assertTrue(saved);
        ArgumentCaptor<QuestionRecord> captor = ArgumentCaptor.forClass(QuestionRecord.class);
        verify(questionRecordMapper, times(1)).insert(captor.capture());
        QuestionRecord record = captor.getValue();
        assertEquals(1L, record.getUserId());
        assertEquals("https://oss.example.com/chat/a.png", record.getImageOssKey());
        assertEquals("已知函数 f(x)=ln(x+1)-x", record.getQuestionText());
        assertEquals("SAVED", record.getRecordStatus());
        verify(questionVectorStoreService).ingestAsync(record);
        verify(similarQuestionMapper, never()).insert(any(SimilarQuestion.class));
    }

    @Test
    void saveFromConversation_photo_noImageMessage() {
        when(conversationMapper.selectById(100L)).thenReturn(conversationOf(1L));
        when(messageMapper.selectList(any())).thenReturn(List.of());

        boolean saved = service.saveFromConversation(1L, 100L, "photo", null, "题目", "解答", null, null);

        assertFalse(saved);
        verify(questionRecordMapper, never()).insert(any(QuestionRecord.class));
        verify(questionVectorStoreService, never()).ingestAsync(any());
    }

    // ---- 相似题（text → similar_question）----

    @Test
    void saveFromConversation_text_shouldInsertSimilarQuestion() {
        when(conversationMapper.selectById(100L)).thenReturn(conversationOf(1L));
        when(similarQuestionMapper.insert(any(SimilarQuestion.class))).thenReturn(1);

        boolean saved = service.saveFromConversation(1L, 100L, "text", 7L,
                "与原题同型的相似题：已知函数 g(x)=e^x-x-1，求极值", "极小值为 -1", null, "数学");

        assertTrue(saved);
        ArgumentCaptor<SimilarQuestion> captor = ArgumentCaptor.forClass(SimilarQuestion.class);
        verify(similarQuestionMapper, times(1)).insert(captor.capture());
        SimilarQuestion similar = captor.getValue();
        assertEquals(1L, similar.getUserId());
        assertEquals(7L, similar.getSourceQuestionId());
        assertEquals(100L, similar.getConversationId());
        assertEquals("与原题同型的相似题：已知函数 g(x)=e^x-x-1，求极值", similar.getQuestionText());
        assertEquals("极小值为 -1", similar.getAnswer());
        assertEquals("数学", similar.getSubject());
        verify(ragIngestService).ingestSimilarQuestionAsync(similar);
        // 相似题不进拍照记录表，也不关联图片
        verify(questionRecordMapper, never()).insert(any(QuestionRecord.class));
        verify(messageMapper, never()).selectList(any());
    }

    @Test
    void saveFromConversation_text_shouldNotAttachStaleImage() {
        // 会话里更早有拍照题目，但本次保存的是相似题：不查图片消息，直接入 similar_question
        when(conversationMapper.selectById(100L)).thenReturn(conversationOf(1L));
        when(similarQuestionMapper.insert(any(SimilarQuestion.class))).thenReturn(1);

        boolean saved = service.saveFromConversation(1L, 100L, "text", null, "相似题题干", "相似题解答", null, null);

        assertTrue(saved);
        ArgumentCaptor<SimilarQuestion> captor = ArgumentCaptor.forClass(SimilarQuestion.class);
        verify(similarQuestionMapper).insert(captor.capture());
        assertNull(captor.getValue().getSourceQuestionId());
        verify(messageMapper, never()).selectList(any());
    }

    @Test
    void saveFromConversation_blankQuestion() {
        boolean saved = service.saveFromConversation(1L, 100L, "photo", null, "  ", "解答", null, null);

        assertFalse(saved);
        verify(questionRecordMapper, never()).insert(any(QuestionRecord.class));
    }

    @Test
    void saveFromConversation_conversationNotOwner() {
        when(conversationMapper.selectById(100L)).thenReturn(conversationOf(2L));

        assertThrows(BusinessException.class,
                () -> service.saveFromConversation(1L, 100L, "photo", null, "题目", "解答", null, null));
        verify(questionRecordMapper, never()).insert(any(QuestionRecord.class));
    }

    // ---- 合并分页列表 ----

    @Test
    void listByUser_mergedPaging() {
        when(questionRecordMapper.countMerged(1L, null, null)).thenReturn(12L);
        when(questionRecordMapper.listMerged(1L, null, null, 10, 0L)).thenReturn(List.of(
                new QuestionItemVO().setId(12L).setSource("similar_ai").setQuestionText("相似题"),
                new QuestionItemVO().setId(3L).setSource("photo").setQuestionText("拍照题")));

        PageVO<QuestionItemVO> result = service.listByUser(1L, null, null, 1, 10);

        assertEquals(12L, result.getTotal());
        assertEquals(10L, result.getSize());
        assertEquals(2, result.getList().size());
        assertEquals("similar_ai", result.getList().get(0).getSource());
        assertEquals(2L, result.getPages());
    }

    // ---- 详情 ----

    @Test
    void getDetail_photo() {
        when(questionRecordMapper.selectById(5L))
                .thenReturn(new QuestionRecord().setId(5L).setUserId(1L).setDeleted(0)
                        .setQuestionText("题五").setImageOssKey("oss://a.png"));

        QuestionItemVO detail = service.getDetail(1L, 5L, "photo");

        assertEquals("photo", detail.getSource());
        assertEquals("题五", detail.getQuestionText());
        assertEquals("oss://a.png", detail.getImageOssKey());
    }

    @Test
    void getDetail_similar() {
        when(similarQuestionMapper.selectById(8L))
                .thenReturn(new SimilarQuestion().setId(8L).setUserId(1L).setDeleted(0)
                        .setQuestionText("相似题八").setAnswer("解答八").setAnalysis("解析八"));

        QuestionItemVO detail = service.getDetail(1L, 8L, "similar_ai");

        assertEquals("similar_ai", detail.getSource());
        assertEquals("相似题八", detail.getQuestionText());
        assertEquals("解答八", detail.getCorrectAnswer());
        assertNull(detail.getImageOssKey());
        assertNull(detail.getIsWrong());
    }

    @Test
    void getDetail_notFound() {
        when(questionRecordMapper.selectById(5L)).thenReturn(null);

        assertThrows(BusinessException.class, () -> service.getDetail(1L, 5L, "photo"));
    }

    @Test
    void getDetail_notOwner() {
        when(questionRecordMapper.selectById(5L))
                .thenReturn(new QuestionRecord().setId(5L).setUserId(2L).setDeleted(0).setQuestionText("题五"));

        assertThrows(BusinessException.class, () -> service.getDetail(1L, 5L, "photo"));
    }

    // ---- 编辑 ----

    @Test
    void updateByUser_photo_onlyProvidedFields() {
        QuestionRecord existing = new QuestionRecord()
                .setId(5L).setUserId(1L).setDeleted(0)
                .setQuestionText("旧题目").setCorrectAnswer("旧解答");
        when(questionRecordMapper.selectById(5L)).thenReturn(existing);
        when(questionRecordMapper.updateById(any(QuestionRecord.class))).thenReturn(1);

        QuestionUpdateDto dto = new QuestionUpdateDto();
        dto.setUserNote("考前重点复习");
        QuestionItemVO updated = service.updateByUser(1L, 5L, "photo", dto);

        assertEquals("旧题目", updated.getQuestionText());
        assertEquals("考前重点复习", updated.getUserNote());
        verify(questionRecordMapper, times(1)).updateById(existing);
    }

    @Test
    void updateByUser_similar_updatesAnswerAndAnalysis() {
        SimilarQuestion existing = new SimilarQuestion()
                .setId(8L).setUserId(1L).setDeleted(0)
                .setQuestionText("旧题干").setAnswer("旧解答");
        when(similarQuestionMapper.selectById(8L)).thenReturn(existing);
        when(similarQuestionMapper.updateById(any(SimilarQuestion.class))).thenReturn(1);

        QuestionUpdateDto dto = new QuestionUpdateDto();
        dto.setCorrectAnswer("新解答");
        dto.setAnalysis("新解析");
        dto.setUserNote("相似题没有作答与笔记字段，应被忽略");
        QuestionItemVO updated = service.updateByUser(1L, 8L, "similar_ai", dto);

        assertEquals("新解答", updated.getCorrectAnswer());
        assertEquals("新解析", updated.getAnalysis());
        assertEquals("similar_ai", updated.getSource());
        verify(ragIngestService).ingestSimilarQuestionAsync(existing);
    }

    // ---- 删除 ----

    @Test
    void deleteByUser_photo_logicalDelete() {
        QuestionRecord existing = new QuestionRecord().setId(5L).setUserId(1L).setDeleted(0);
        when(questionRecordMapper.selectById(5L)).thenReturn(existing);
        when(questionRecordMapper.updateById(any(QuestionRecord.class))).thenReturn(1);

        service.deleteByUser(1L, 5L, "photo");

        assertEquals(1, existing.getDeleted());
        assertNotNull(existing.getUpdatedAt());
        verify(questionRecordMapper, times(1)).updateById(existing);
        verify(questionVectorStoreService).remove(5L);
    }

    @Test
    void deleteByUser_similar_logicalDeleteAndVectorRemove() {
        SimilarQuestion existing = new SimilarQuestion().setId(8L).setUserId(1L).setDeleted(0);
        when(similarQuestionMapper.selectById(8L)).thenReturn(existing);
        when(similarQuestionMapper.updateById(any(SimilarQuestion.class))).thenReturn(1);

        service.deleteByUser(1L, 8L, "similar_ai");

        assertEquals(1, existing.getDeleted());
        verify(similarQuestionMapper, times(1)).updateById(existing);
        verify(ragIngestService).removeSimilar(8L);
    }
}
