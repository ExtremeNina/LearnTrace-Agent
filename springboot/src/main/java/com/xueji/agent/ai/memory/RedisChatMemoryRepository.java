package com.xueji.agent.ai.memory;

import cn.hutool.json.JSONUtil;
import com.xueji.agent.common.RedisKeys;
import org.springframework.ai.chat.memory.ChatMemoryRepository;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * 基于 Redis 的 ChatMemoryRepository：
 * 每个会话一个键（RedisKeys.CHAT_MEMORY_PREFIX + conversationId），
 * 值为 MemoryMessage 的 JSON 数组；saveAll 语义为整体替换。
 */
public class RedisChatMemoryRepository implements ChatMemoryRepository {

    private final StringRedisTemplate stringRedisTemplate;

    public RedisChatMemoryRepository(StringRedisTemplate stringRedisTemplate) {
        this.stringRedisTemplate = stringRedisTemplate;
    }

    @Override
    public List<String> findConversationIds() {
        Set<String> keys = stringRedisTemplate.keys(RedisKeys.CHAT_MEMORY_PREFIX + "*");
        List<String> ids = new ArrayList<>();
        if (keys == null) {
            return ids;
        }
        for (String key : keys) {
            ids.add(key.substring(RedisKeys.CHAT_MEMORY_PREFIX.length()));
        }
        return ids;
    }

    @Override
    public List<Message> findByConversationId(String conversationId) {
        String json = stringRedisTemplate.opsForValue().get(RedisKeys.CHAT_MEMORY_PREFIX + conversationId);
        List<Message> messages = new ArrayList<>();
        if (json == null || json.isBlank()) {
            return messages;
        }
        List<MemoryMessage> stored = JSONUtil.toList(json, MemoryMessage.class);
        for (MemoryMessage m : stored) {
            switch (m.getRole()) {
                case "user" -> messages.add(new UserMessage(m.getText()));
                case "assistant" -> messages.add(new AssistantMessage(m.getText()));
                case "system" -> messages.add(new SystemMessage(m.getText()));
                default -> {
                    // 其他角色（工具消息等）暂不参与记忆窗口
                }
            }
        }
        return messages;
    }

    @Override
    public void saveAll(String conversationId, List<Message> messages) {
        List<MemoryMessage> stored = new ArrayList<>();
        for (Message message : messages) {
            stored.add(new MemoryMessage(resolveRole(message), message.getText()));
        }
        String key = RedisKeys.CHAT_MEMORY_PREFIX + conversationId;
        if (stored.isEmpty()) {
            stringRedisTemplate.delete(key);
            return;
        }
        stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(stored));
    }

    @Override
    public void deleteByConversationId(String conversationId) {
        stringRedisTemplate.delete(RedisKeys.CHAT_MEMORY_PREFIX + conversationId);
    }

    private String resolveRole(Message message) {
        if (message instanceof UserMessage) {
            return "user";
        }
        if (message instanceof AssistantMessage) {
            return "assistant";
        }
        if (message instanceof SystemMessage) {
            return "system";
        }
        return "tool";
    }
}
