package com.xueji.agent.service;

import com.xueji.agent.domain.dto.QuestionUpdateDto;
import com.xueji.agent.domain.entity.Conversation;
import com.xueji.agent.domain.entity.Message;
import com.xueji.agent.domain.entity.QuestionRecord;
import com.xueji.agent.domain.vo.PageVO;
import com.xueji.agent.exception.BusinessException;
import com.xueji.agent.mapper.ConversationMapper;
import com.xueji.agent.mapper.MessageMapper;
import com.xueji.agent.mapper.QuestionRecordMapper;
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
 * 题目记录服务基础功能测试：保存（成功 / 无题目 / 空内容 / 会话非本人）、
 * 分页列表、详情、编辑、删除
 */
class QuestionServiceImplTest {

    private QuestionRecordMapper questionRecordMapper;
    private ConversationMapper conversationMapper;
    private MessageMapper messageMapper;
    private QuestionVectorStoreService questionVectorStoreService;
    private QuestionServiceImpl service;

    @BeforeEach
    void setUp() {
        questionRecordMapper = mock(QuestionRecordMapper.class);
        conversationMapper = mock(ConversationMapper.class);
        messageMapper = mock(MessageMapper.class);
        questionVectorStoreService = mock(QuestionVectorStoreService.class);
        service = new QuestionServiceImpl();
        ReflectionTestUtils.setField(service, "questionRecordMapper", questionRecordMapper);
        ReflectionTestUtils.setField(service, "conversationMapper", conversationMapper);
        ReflectionTestUtils.setField(service, "messageMapper", messageMapper);
        ReflectionTestUtils.setField(service, "questionVectorStoreService", questionVectorStoreService);
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

    @Test
    void saveFromConversation_success() {
        when(conversationMapper.selectById(100L)).thenReturn(conversationOf(1L));
        when(messageMapper.selectList(any())).thenReturn(List.of(
                questionMessageOf(10L, "{\"imageUrl\":\"https://oss.example.com/chat/a.png\"}")));
        when(questionRecordMapper.insert(any(QuestionRecord.class))).thenReturn(1);

        boolean saved = service.saveFromConversation(1L, 100L, "photo", "已知函数 f(x)=ln(x+1)-x", "极大值为 0", null, "数学");

        assertTrue(saved);
        ArgumentCaptor<QuestionRecord> captor = ArgumentCaptor.forClass(QuestionRecord.class);
        verify(questionRecordMapper, times(1)).insert(captor.capture());
        QuestionRecord record = captor.getValue();
        assertEquals(1L, record.getUserId());
        assertEquals("https://oss.example.com/chat/a.png", record.getImageOssKey());
        assertEquals("已知函数 f(x)=ln(x+1)-x", record.getQuestionText());
        assertEquals("数学", record.getSubject());
        assertEquals("极大值为 0", record.getCorrectAnswer());
        assertEquals("SUCCESS", record.getAiStatus());
        assertEquals("SAVED", record.getRecordStatus());
        verify(questionVectorStoreService).ingestAsync(record);
    }

    @Test
    void saveFromConversation_noQuestion() {
        when(conversationMapper.selectById(100L)).thenReturn(conversationOf(1L));
        when(messageMapper.selectList(any())).thenReturn(List.of());

        boolean saved = service.saveFromConversation(1L, 100L, "photo", "题目", "解答", null, null);

        assertFalse(saved);
        verify(questionRecordMapper, never()).insert(any(QuestionRecord.class));
        verify(questionVectorStoreService, never()).ingestAsync(any());
    }

    @Test
    void saveFromConversation_textSourceShouldSaveWithoutImage() {
        // RAG 相似题 / 手打题为纯文字来源：不查图片消息，imageOssKey 为 null，其余通道不变
        when(conversationMapper.selectById(100L)).thenReturn(conversationOf(1L));
        when(questionRecordMapper.insert(any(QuestionRecord.class))).thenReturn(1);

        boolean saved = service.saveFromConversation(1L, 100L, "text",
                "与原题同型的相似题：已知函数 g(x)=e^x-x-1，求极值", "极小值为 -1", null, "数学");

        assertTrue(saved);
        ArgumentCaptor<QuestionRecord> captor = ArgumentCaptor.forClass(QuestionRecord.class);
        verify(questionRecordMapper, times(1)).insert(captor.capture());
        QuestionRecord record = captor.getValue();
        assertNull(record.getImageOssKey());
        assertEquals("与原题同型的相似题：已知函数 g(x)=e^x-x-1，求极值", record.getQuestionText());
        assertEquals("SAVED", record.getRecordStatus());
        // 无图题目同样入库向量，RAG 可再次召回
        verify(questionVectorStoreService).ingestAsync(record);
        verify(messageMapper, never()).selectList(any());
    }

    @Test
    void saveFromConversation_textSourceShouldNotAttachStaleImage() {
        // 会话里更早有拍照题目，但本次保存的是相似题：不得把旧图挂到新记录上
        when(conversationMapper.selectById(100L)).thenReturn(conversationOf(1L));
        when(questionRecordMapper.insert(any(QuestionRecord.class))).thenReturn(1);

        boolean saved = service.saveFromConversation(1L, 100L, "text", "相似题题干", "相似题解答", null, null);

        assertTrue(saved);
        ArgumentCaptor<QuestionRecord> captor = ArgumentCaptor.forClass(QuestionRecord.class);
        verify(questionRecordMapper).insert(captor.capture());
        assertNull(captor.getValue().getImageOssKey());
        verify(messageMapper, never()).selectList(any());
    }

    @Test
    void saveFromConversation_blankQuestion() {
        boolean saved = service.saveFromConversation(1L, 100L, "photo", "  ", "解答", null, null);

        assertFalse(saved);
        verify(questionRecordMapper, never()).insert(any(QuestionRecord.class));
    }

    @Test
    void saveFromConversation_conversationNotOwner() {
        when(conversationMapper.selectById(100L)).thenReturn(conversationOf(2L));

        assertThrows(BusinessException.class,
                () -> service.saveFromConversation(1L, 100L, "photo", "题目", "解答", null, null));
        verify(questionRecordMapper, never()).insert(any(QuestionRecord.class));
    }

    @Test
    void listByUser_paging() {
        when(questionRecordMapper.selectCount(any())).thenReturn(12L);
        when(questionRecordMapper.selectList(any())).thenReturn(List.of(
                new QuestionRecord().setId(12L).setUserId(1L),
                new QuestionRecord().setId(3L).setUserId(1L)));

        PageVO<QuestionRecord> result = service.listByUser(1L, null, null, 1, 10);

        assertEquals(12L, result.getTotal());
        assertEquals(10L, result.getSize());
        assertEquals(2, result.getList().size());
        assertEquals(2L, result.getPages());
    }

    @Test
    void getDetail_success() {
        when(questionRecordMapper.selectById(5L))
                .thenReturn(new QuestionRecord().setId(5L).setUserId(1L).setDeleted(0).setQuestionText("题五"));

        QuestionRecord detail = service.getDetail(1L, 5L);

        assertEquals("题五", detail.getQuestionText());
    }

    @Test
    void getDetail_notFound() {
        when(questionRecordMapper.selectById(5L)).thenReturn(null);

        assertThrows(BusinessException.class, () -> service.getDetail(1L, 5L));
    }

    @Test
    void getDetail_notOwner() {
        when(questionRecordMapper.selectById(5L))
                .thenReturn(new QuestionRecord().setId(5L).setUserId(2L).setDeleted(0).setQuestionText("题五"));

        assertThrows(BusinessException.class, () -> service.getDetail(1L, 5L));
    }

    @Test
    void updateByUser_onlyProvidedFields() {
        QuestionRecord existing = new QuestionRecord()
                .setId(5L).setUserId(1L).setDeleted(0)
                .setQuestionText("旧题目").setCorrectAnswer("旧解答");
        when(questionRecordMapper.selectById(5L)).thenReturn(existing);
        when(questionRecordMapper.updateById(any(QuestionRecord.class))).thenReturn(1);

        QuestionUpdateDto dto = new QuestionUpdateDto();
        dto.setUserNote("考前重点复习");
        QuestionRecord updated = service.updateByUser(1L, 5L, dto);

        assertEquals("旧题目", updated.getQuestionText());
        assertEquals("考前重点复习", updated.getUserNote());
        verify(questionRecordMapper, times(1)).updateById(updated);
    }

    @Test
    void deleteByUser_logicalDelete() {
        QuestionRecord existing = new QuestionRecord().setId(5L).setUserId(1L).setDeleted(0);
        when(questionRecordMapper.selectById(5L)).thenReturn(existing);
        when(questionRecordMapper.updateById(any(QuestionRecord.class))).thenReturn(1);

        service.deleteByUser(1L, 5L);

        assertEquals(1, existing.getDeleted());
        assertNotNull(existing.getUpdatedAt());
        verify(questionRecordMapper, times(1)).updateById(existing);
    }
}
