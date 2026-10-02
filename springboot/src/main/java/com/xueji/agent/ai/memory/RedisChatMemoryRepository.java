package com.xueji.agent.ai.memory;

import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.xueji.agent.common.RedisKeys;
import com.xueji.agent.mapper.MessageMapper;
import lombok.extern.slf4j.Slf4j;
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
 *
 * 读路径一致性兜底（PRD §6）：Redis 缓存缺失（清空 / 逐出 / 丢失）时从 MySQL 事实源（message 表）
 * 重建补齐——任何时刻清空 Redis，LLM 会话记忆不变。仅对数字会话 ID（Agent 会话）生效，
 * 网课笔记等无事实源的独立记忆空间（如 course-note-*）跳过重建。
 */
@Slf4j
public class RedisChatMemoryRepository implements ChatMemoryRepository {

    private final StringRedisTemplate stringRedisTemplate;

    private final MessageMapper messageMapper;

    /** 重建时回填的最大消息条数（与会话记忆滑窗 max-messages 一致） */
    private final int rebuildWindow;

    public RedisChatMemoryRepository(StringRedisTemplate stringRedisTemplate,
                                     MessageMapper messageMapper, int rebuildWindow) {
        this.stringRedisTemplate = stringRedisTemplate;
        this.messageMapper = messageMapper;
        this.rebuildWindow = rebuildWindow;
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
        if ((json == null || json.isBlank()) && isAgentConversation(conversationId)) {
            // 缓存缺失：从事实源重建补齐后回填 Redis
            List<Message> rebuilt = rebuildFromDb(conversationId);
            if (!rebuilt.isEmpty()) {
                saveAll(conversationId, rebuilt);
            }
            return rebuilt;
        }
        return parseStored(json);
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

    /**
     * 从 message 表重建会话热上下文：取最近 rebuildWindow 条 user/assistant 消息。
     * 末尾未配对的用户消息（回合进行中刚落库的事实源）不进记忆——本轮提示词会由记忆 Advisor
     * 再次写入，重建时带上会导致同一条消息在记忆中重复。
     * 重建失败不阻塞回合，降级为无历史记忆。
     */
    private List<Message> rebuildFromDb(String conversationId) {
        try {
            // 最近 N 条：按 id 倒序取窗口再翻转回时间正序
            List<com.xueji.agent.domain.entity.Message> rows = messageMapper.selectList(new QueryWrapper<com.xueji.agent.domain.entity.Message>()
                    .eq("conversation_id", Long.valueOf(conversationId))
                    .in("role", "user", "assistant")
                    .orderByDesc("id")
                    .last("LIMIT " + rebuildWindow));
            List<com.xueji.agent.domain.entity.Message> chronological = new ArrayList<>();
            if (rows != null) {
                for (int i = rows.size() - 1; i >= 0; i--) {
                    chronological.add(rows.get(i));
                }
            }
            if (!chronological.isEmpty() && "user".equals(chronological.get(chronological.size() - 1).getRole())) {
                chronological.remove(chronological.size() - 1);
            }
            List<Message> messages = new ArrayList<>();
            for (com.xueji.agent.domain.entity.Message row : chronological) {
                if ("user".equals(row.getRole())) {
                    messages.add(new UserMessage(assembleUserContent(row)));
                } else if ("assistant".equals(row.getRole())) {
                    messages.add(new AssistantMessage(row.getContent() == null ? "" : row.getContent()));
                }
            }
            log.info("会话记忆自 MySQL 重建, conversationId={}, 条数={}", conversationId, messages.size());
            return messages;
        } catch (Exception e) {
            log.warn("会话记忆重建失败（降级为无历史记忆）, conversationId={}", conversationId, e);
            return new ArrayList<>();
        }
    }

    /**
     * 重建用户消息内容：原文 + 题目识别附录（与 AgentPrompts 的带图回合组装规则一致，
     * 保证重建后的记忆与原先写入记忆的提示内容形态相同）
     */
    private String assembleUserContent(com.xueji.agent.domain.entity.Message row) {
        String content = row.getContent() == null ? "" : row.getContent();
        String questionText = null;
        if (row.getPayload() != null && !row.getPayload().isBlank()) {
            try {
                questionText = cn.hutool.json.JSONUtil.parseObj(row.getPayload()).getStr("questionText", null);
            } catch (Exception e) {
                log.warn("记忆重建 payload 解析失败, messageId={}", row.getId());
            }
        }
        if (questionText == null || questionText.isBlank()) {
            return content;
        }
        return content + "\n\n[题目图片识别文本（已整理）]\n" + questionText;
    }

    private boolean isAgentConversation(String conversationId) {
        try {
            Long.parseLong(conversationId);
            return true;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    private List<Message> parseStored(String json) {
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
