package com.xueji.agent.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.xueji.agent.common.OwnershipCheck;
import com.xueji.agent.domain.entity.Conversation;
import com.xueji.agent.domain.entity.Message;
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

    /** 自动标题最大长度 */
    private static final int MAX_TITLE_LEN = 20;

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
        return OwnershipCheck.requireOwned(conversationMapper.selectById(conversationId), userId, "会话不存在");
    }

    @Override
    public void applyTitleFromFirstMessage(Long userId, Long conversationId, String firstMessage) {
        Conversation conversation = conversationMapper.selectById(conversationId);
        if (conversation == null || !conversation.getUserId().equals(userId)) {
            return;
        }
        // 已有正式标题（非默认）的会话不再重命名
        if (!DEFAULT_TITLE.equals(conversation.getTitle())) {
            return;
        }
        String base = deriveTitle(firstMessage);
        if (base.isBlank()) {
            return;
        }
        // 与该用户其他会话的标题去重：重名追加序号（2）（3）…
        List<Conversation> others = conversationMapper.selectList(new QueryWrapper<Conversation>()
                .eq("user_id", userId)
                .ne("id", conversationId));
        java.util.Set<String> usedTitles = new java.util.HashSet<>();
        for (Conversation other : others) {
            usedTitles.add(other.getTitle());
        }
        String candidate = base;
        int seq = 2;
        while (usedTitles.contains(candidate)) {
            candidate = base + "（" + seq + "）";
            seq++;
        }
        rename(userId, conversationId, candidate);
    }

    /**
     * 从首条消息提取标题：压平空白，截断到 20 字并加省略号
     */
    private String deriveTitle(String firstMessage) {
        String text = firstMessage == null ? "" : firstMessage.replaceAll("\\s+", " ").trim();
        if (text.length() > MAX_TITLE_LEN) {
            text = text.substring(0, MAX_TITLE_LEN) + "…";
        }
        return text;
    }

    @Override
    public long countUserMessages(Long userId, Long conversationId) {
        checkOwnership(userId, conversationId);
        return messageMapper.selectCount(new QueryWrapper<Message>()
                .eq("conversation_id", conversationId)
                .eq("role", "user"));
    }

    @Override
    public int cleanupExpiredConversations() {
        LocalDateTime threshold = LocalDateTime.now().minusDays(RETENTION_DAYS);
        List<Conversation> expired = conversationMapper.selectList(new QueryWrapper<Conversation>()
                .lt("last_active_at", threshold));
        for (Conversation conversation : expired) {
            messageMapper.delete(new QueryWrapper<Message>().eq("conversation_id", conversation.getId()));
            // 同步清理 Redis 会话记忆
            chatMemory.clear(String.valueOf(conversation.getId()));
            conversationMapper.deleteById(conversation.getId());
        }
        return expired.size();
    }
}
