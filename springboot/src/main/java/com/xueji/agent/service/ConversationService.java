package com.xueji.agent.service;

import com.xueji.agent.domain.entity.Conversation;
import com.xueji.agent.domain.entity.Message;

import java.util.List;

/**
 * 会话管理：增删改查（会话属于临时数据，删除不影响学习资产）
 */
public interface ConversationService {

    /** 单个会话的对话轮次上限（按用户消息数计） */
    int MAX_TURNS_PER_CONVERSATION = 100;

    /** 会话保留天数：超过该天数未活跃的会话由定时任务清理 */
    int RETENTION_DAYS = 30;

    /**
     * 创建会话
     */
    Conversation create(Long userId, String title);

    /**
     * 用户会话列表（按最近活跃排序）
     */
    List<Conversation> listByUser(Long userId);

    /**
     * 会话内消息（按时间正序）
     */
    List<Message> messages(Long userId, Long conversationId);

    /**
     * 重命名
     */
    void rename(Long userId, Long conversationId, String title);

    /**
     * 删除会话及其消息（不删除学习资产）
     */
    void delete(Long userId, Long conversationId);

    /**
     * 会话仍为默认标题时，用首条用户消息生成标题（截断 + 与用户其他会话去重）
     */
    void applyTitleFromFirstMessage(Long userId, Long conversationId, String firstMessage);

    /**
     * 统计会话内的用户消息数（对话轮次），用于会话轮次上限校验
     */
    long countUserMessages(Long userId, Long conversationId);

    /**
     * 清理超过保留天数未活跃的会话（连带消息与 Redis 会话记忆），返回清理数量
     */
    int cleanupExpiredConversations();
}
