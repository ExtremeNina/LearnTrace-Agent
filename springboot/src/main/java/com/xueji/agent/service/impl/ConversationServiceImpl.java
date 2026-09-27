package com.xueji.agent.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.xueji.agent.domain.entity.Conversation;
import com.xueji.agent.domain.entity.Message;
import com.xueji.agent.exception.BusinessException;
import com.xueji.agent.mapper.ConversationMapper;
import com.xueji.agent.mapper.MessageMapper;
import com.xueji.agent.service.ConversationService;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;

@Service
public class ConversationServiceImpl implements ConversationService {

    private static final String DEFAULT_TITLE = "新对话";

    @Resource
    private ConversationMapper conversationMapper;

    @Resource
    private MessageMapper messageMapper;

    @Resource
    private org.springframework.ai.chat.memory.ChatMemory chatMemory;

    @Override
    public Conversation create(Long userId, String title) {
        Conversation conversation = new Conversation()
                .setUserId(userId)
                .setTitle(title == null || title.isBlank() ? DEFAULT_TITLE : title)
                .setLastActiveAt(LocalDateTime.now());
        conversationMapper.insert(conversation);
        return conversation;
    }

    @Override
    public List<Conversation> listByUser(Long userId) {
        return conversationMapper.selectList(new QueryWrapper<Conversation>()
                .eq("user_id", userId)
                .orderByDesc("last_active_at"));
    }

    @Override
    public List<Message> messages(Long userId, Long conversationId) {
        checkOwnership(userId, conversationId);
        return messageMapper.selectList(new QueryWrapper<Message>()
                .eq("conversation_id", conversationId)
                .orderByAsc("id"));
    }

    @Override
    public void rename(Long userId, Long conversationId, String title) {
        checkOwnership(userId, conversationId);
        Conversation conversation = new Conversation()
                .setId(conversationId)
                .setTitle(title)
                .setUpdatedAt(LocalDateTime.now());
        conversationMapper.updateById(conversation);
    }

    @Override
    public void delete(Long userId, Long conversationId) {
        checkOwnership(userId, conversationId);
        conversationMapper.deleteById(conversationId);
        messageMapper.delete(new QueryWrapper<Message>().eq("conversation_id", conversationId));
        // 同步清理 Redis 会话记忆
        chatMemory.clear(String.valueOf(conversationId));
    }

    /**
     * 校验会话归属，防止越权访问他人会话
     */
    private Conversation checkOwnership(Long userId, Long conversationId) {
        Conversation conversation = conversationMapper.selectById(conversationId);
        if (conversation == null || !conversation.getUserId().equals(userId)) {
            throw new BusinessException(404, "会话不存在");
        }
        return conversation;
    }
}
