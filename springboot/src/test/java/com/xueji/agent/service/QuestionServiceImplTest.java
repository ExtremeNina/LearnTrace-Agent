package com.xueji.agent.service;

import com.xueji.agent.domain.entity.Conversation;
import com.xueji.agent.domain.entity.Message;
import com.xueji.agent.domain.entity.QuestionRecord;
import com.xueji.agent.exception.BusinessException;
import com.xueji.agent.mapper.ConversationMapper;
import com.xueji.agent.mapper.MessageMapper;
import com.xueji.agent.mapper.QuestionRecordMapper;
import com.xueji.agent.service.impl.QuestionServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 题目记录服务基础功能测试：保存（成功 / 无题目 / 会话非本人）、列表、详情
 */
class QuestionServiceImplTest {

    private QuestionRecordMapper questionRecordMapper;
    private ConversationMapper conversationMapper;
    private MessageMapper messageMapper;
    private QuestionServiceImpl service;

    @BeforeEach
    void setUp() {
        questionRecordMapper = mock(QuestionRecordMapper.class);
        conversationMapper = mock(ConversationMapper.class);
        messageMapper = mock(MessageMapper.class);
        service = new QuestionServiceImpl();
        ReflectionTestUtils.setField(service, "questionRecordMapper", questionRecordMapper);
        ReflectionTestUtils.setField(service, "conversationMapper", conversationMapper);
        ReflectionTestUtils.setField(service, "messageMapper", messageMapper);
    }

    private Conversation conversationOf(Long userId) {
        return new Conversation().setId(100L).setUserId(userId);
    }

    private Message messageOf(Long id, String role, String content, String payload) {
        Message message = new Message()
                .setConversationId(100L)
                .setRole(role)
                .setContent(content);
        if (payload != null) {
            message.setPayload(payload);
        }
        message.setId(id);
        return message;
    }

    @Test
    void saveFromConversation_success() {
        when(conversationMapper.selectById(100L)).thenReturn(conversationOf(1L));
        when(messageMapper.selectList(any())).thenReturn(
                List.of(messageOf(10L, "user", "请看这张图片",
                        "{\"imageUrl\":\"https://oss.example.com/chat/a.png\",\"questionText\":\"已知函数 f(x)=ln(x+1)-x\"}")),
                List.of(messageOf(11L, "assistant", "极大值为 0", null)));
        when(questionRecordMapper.insert(any(QuestionRecord.class))).thenReturn(1);

        boolean saved = service.saveFromConversation(1L, 100L);

        assertTrue(saved);
        ArgumentCaptor<QuestionRecord> captor = ArgumentCaptor.forClass(QuestionRecord.class);
        verify(questionRecordMapper, times(1)).insert(captor.capture());
        QuestionRecord record = captor.getValue();
        assertEquals(1L, record.getUserId());
        assertEquals("https://oss.example.com/chat/a.png", record.getImageOssKey());
        assertEquals("已知函数 f(x)=ln(x+1)-x", record.getQuestionText());
        assertEquals("极大值为 0", record.getCorrectAnswer());
        assertEquals("SUCCESS", record.getAiStatus());
        assertEquals("SAVED", record.getRecordStatus());
    }

    @Test
    void saveFromConversation_noQuestion() {
        when(conversationMapper.selectById(100L)).thenReturn(conversationOf(1L));
        when(messageMapper.selectList(any())).thenReturn(List.of());

        boolean saved = service.saveFromConversation(1L, 100L);

        assertFalse(saved);
        verify(questionRecordMapper, never()).insert(any(QuestionRecord.class));
    }

    @Test
    void saveFromConversation_conversationNotOwner() {
        when(conversationMapper.selectById(100L)).thenReturn(conversationOf(2L));

        assertThrows(BusinessException.class, () -> service.saveFromConversation(1L, 100L));
        verify(questionRecordMapper, never()).insert(any(QuestionRecord.class));
    }

    @Test
    void listByUser_returnsRecords() {
        when(questionRecordMapper.selectList(any())).thenReturn(List.of(
                new QuestionRecord().setId(1L).setUserId(1L).setQuestionText("题一"),
                new QuestionRecord().setId(2L).setUserId(1L).setQuestionText("题二")));

        List<QuestionRecord> list = service.listByUser(1L);

        assertEquals(2, list.size());
        assertEquals("题一", list.get(0).getQuestionText());
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
}
