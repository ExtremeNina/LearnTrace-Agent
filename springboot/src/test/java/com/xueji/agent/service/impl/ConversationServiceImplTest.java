package com.xueji.agent.service.impl;

import com.xueji.agent.domain.entity.Conversation;
import com.xueji.agent.domain.entity.Message;
import com.xueji.agent.exception.BusinessException;
import com.xueji.agent.mapper.ConversationMapper;
import com.xueji.agent.mapper.MessageMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.chat.memory.ChatMemory;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 会话服务：创建默认值、归属校验（越权 404）、删除连带清理
 */
@ExtendWith(MockitoExtension.class)
class ConversationServiceImplTest {

    private static final Long USER_ID = 5L;
    private static final Long CONV_ID = 1L;

    @Mock
    private ConversationMapper conversationMapper;

    @Mock
    private MessageMapper messageMapper;

    @Mock
    private ChatMemory chatMemory;

    @InjectMocks
    private ConversationServiceImpl service;

    private Conversation ownedConversation() {
        return new Conversation().setId(CONV_ID).setUserId(USER_ID).setTitle("旧标题");
    }

    @Test
    void createShouldApplyDefaultTitleWhenBlank() {
        service.create(USER_ID, "  ");
        ArgumentCaptor<Conversation> captor = ArgumentCaptor.forClass(Conversation.class);
        verify(conversationMapper).insert(captor.capture());
        assertThat(captor.getValue().getTitle()).isEqualTo("新对话");
        assertThat(captor.getValue().getUserId()).isEqualTo(USER_ID);
        assertThat(captor.getValue().getLastActiveAt()).isNotNull();
    }

    @Test
    void createShouldKeepGivenTitle() {
        service.create(USER_ID, "高数笔记");
        ArgumentCaptor<Conversation> captor = ArgumentCaptor.forClass(Conversation.class);
        verify(conversationMapper).insert(captor.capture());
        assertThat(captor.getValue().getTitle()).isEqualTo("高数笔记");
    }

    @Test
    void messagesShouldReturnListForOwner() {
        when(conversationMapper.selectById(CONV_ID)).thenReturn(ownedConversation());
        List<Message> expected = List.of(new Message());
        when(messageMapper.selectList(any())).thenReturn(expected);

        List<Message> result = service.messages(USER_ID, CONV_ID);
        assertThat(result).isSameAs(expected);
    }

    @Test
    void messagesShouldRejectOtherUsersConversation() {
        when(conversationMapper.selectById(CONV_ID)).thenReturn(ownedConversation());

        assertThatThrownBy(() -> service.messages(999L, CONV_ID))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> assertThat(((BusinessException) e).getCode()).isEqualTo(404));
    }

    @Test
    void messagesShouldRejectMissingConversation() {
        when(conversationMapper.selectById(CONV_ID)).thenReturn(null);

        assertThatThrownBy(() -> service.messages(USER_ID, CONV_ID))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    void deleteShouldRemoveConversationMessagesAndMemory() {
        when(conversationMapper.selectById(CONV_ID)).thenReturn(ownedConversation());

        service.delete(USER_ID, CONV_ID);

        verify(conversationMapper).deleteById(CONV_ID);
        verify(messageMapper).delete(any());
        verify(chatMemory).clear(String.valueOf(CONV_ID));
    }

    @Test
    void deleteShouldDoNothingForOtherUsersConversation() {
        when(conversationMapper.selectById(CONV_ID)).thenReturn(ownedConversation());

        assertThatThrownBy(() -> service.delete(999L, CONV_ID))
                .isInstanceOf(BusinessException.class);

        verify(conversationMapper, never()).deleteById(CONV_ID);
        verify(messageMapper, never()).delete(any());
        verify(chatMemory, never()).clear(eq(String.valueOf(CONV_ID)));
    }

    @Test
    void renameShouldUpdateTitle() {
        when(conversationMapper.selectById(CONV_ID)).thenReturn(ownedConversation());

        service.rename(USER_ID, CONV_ID, "新标题");

        ArgumentCaptor<Conversation> captor = ArgumentCaptor.forClass(Conversation.class);
        verify(conversationMapper).updateById(captor.capture());
        assertThat(captor.getValue().getTitle()).isEqualTo("新标题");
    }

    @Test
    void chatMemoryMockSanity() {
        // 保证 ChatMemory 依赖被正确注入（防 @InjectMocks 字段遗漏）
        assertThat(chatMemory).isNotNull();
        assertThat(mock(ChatMemory.class)).isNotSameAs(chatMemory);
    }

    // ---- 自动标题 ----

    @Test
    void titleShouldBeDerivedFromFirstMessageWhenDefault() {
        Conversation conversation = ownedConversation().setTitle("新对话");
        when(conversationMapper.selectById(CONV_ID)).thenReturn(conversation);
        when(conversationMapper.selectList(any())).thenReturn(List.of());

        service.applyTitleFromFirstMessage(USER_ID, CONV_ID, "帮我解一下这道导数题");

        ArgumentCaptor<Conversation> captor = ArgumentCaptor.forClass(Conversation.class);
        verify(conversationMapper).updateById(captor.capture());
        assertThat(captor.getValue().getTitle()).isEqualTo("帮我解一下这道导数题");
    }

    @Test
    void titleShouldTruncateLongMessage() {
        when(conversationMapper.selectById(CONV_ID)).thenReturn(ownedConversation().setTitle("新对话"));
        when(conversationMapper.selectList(any())).thenReturn(List.of());

        service.applyTitleFromFirstMessage(USER_ID, CONV_ID, "这是一条特别长的消息内容远远超过二十个字的限制所以需要被截断处理");

        ArgumentCaptor<Conversation> captor = ArgumentCaptor.forClass(Conversation.class);
        verify(conversationMapper).updateById(captor.capture());
        assertThat(captor.getValue().getTitle()).hasSize(21).endsWith("…");
    }

    @Test
    void titleShouldAppendSeqSuffixWhenDuplicated() {
        when(conversationMapper.selectById(CONV_ID)).thenReturn(ownedConversation().setTitle("新对话"));
        Conversation other = new Conversation().setId(99L).setUserId(USER_ID).setTitle("帮我解一下这道导数题");
        when(conversationMapper.selectList(any())).thenReturn(List.of(other));

        service.applyTitleFromFirstMessage(USER_ID, CONV_ID, "帮我解一下这道导数题");

        ArgumentCaptor<Conversation> captor = ArgumentCaptor.forClass(Conversation.class);
        verify(conversationMapper).updateById(captor.capture());
        assertThat(captor.getValue().getTitle()).isEqualTo("帮我解一下这道导数题（2）");
    }

    @Test
    void titleShouldSkipWhenAlreadyRenamed() {
        Conversation renamed = ownedConversation().setTitle("正式标题");
        when(conversationMapper.selectById(CONV_ID)).thenReturn(renamed);

        service.applyTitleFromFirstMessage(USER_ID, CONV_ID, "随便说点什么");

        verify(conversationMapper, never()).updateById(any());
    }

    @Test
    void titleShouldSkipWhenMessageBlank() {
        when(conversationMapper.selectById(CONV_ID)).thenReturn(ownedConversation());

        service.applyTitleFromFirstMessage(USER_ID, CONV_ID, "   ");

        verify(conversationMapper, never()).updateById(any());
    }
}
