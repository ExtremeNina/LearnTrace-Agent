package com.xueji.agent.service;

import com.xueji.agent.domain.entity.Conversation;
import com.xueji.agent.domain.entity.Message;

import java.util.List;

/**
 * 会话管理：增删改查（会话属于临时数据，删除不影响学习资产）
 */
public interface ConversationService {

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
}
