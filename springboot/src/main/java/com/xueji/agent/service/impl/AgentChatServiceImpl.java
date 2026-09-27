package com.xueji.agent.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.xueji.agent.domain.entity.Conversation;
import com.xueji.agent.domain.entity.Message;
import com.xueji.agent.domain.vo.ChatEvent;
import com.xueji.agent.exception.BusinessException;
import com.xueji.agent.mapper.ConversationMapper;
import com.xueji.agent.mapper.MessageMapper;
import com.xueji.agent.service.AgentChatService;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import reactor.core.publisher.Flux;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Agent 对话实现：
 * 记忆——由 MessageChatMemoryAdvisor（RedisChatMemoryRepository）自动读写，
 * 本类只负责事实源（message 表）的双写与事件流编排。
 */
@Slf4j
@Service
public class AgentChatServiceImpl implements AgentChatService {

    @Resource
    private ChatClient chatClient;

    @Resource
    private ConversationMapper conversationMapper;

    @Resource
    private MessageMapper messageMapper;

    @Override
    public Flux<ChatEvent> chat(Long userId, Long conversationId, String content, String imageUrl) {
        Conversation conversation = conversationMapper.selectById(conversationId);
        if (conversation == null || !conversation.getUserId().equals(userId)) {
            throw new BusinessException(404, "会话不存在");
        }

        String turnId = "t_" + java.util.UUID.randomUUID().toString().substring(0, 8);

        // 用户消息先落事实源（回合中途崩溃也不丢用户输入）；附图链接记入 payload
        Message userMessage = new Message()
                .setConversationId(conversationId)
                .setRole("user")
                .setMsgType("text")
                .setContent(content)
                .setCreatedAt(LocalDateTime.now());
        if (imageUrl != null && !imageUrl.isBlank()) {
            userMessage.setPayload(cn.hutool.json.JSONUtil.createObj().set("imageUrl", imageUrl).toString());
        }
        messageMapper.insert(userMessage);

        // DeepSeek 平台 API 初期为纯文本（PRD §11 不引入多模态）：图片以链接形式附在 prompt 中
        String promptContent = content;
        if (imageUrl != null && !imageUrl.isBlank()) {
            promptContent = content + "\n\n[用户附带了一张图片，链接: " + imageUrl + "]";
        }

        StringBuilder answer = new StringBuilder();
        long[] savedMessageId = new long[1];

        Flux<ChatEvent> body = chatClient.prompt()
                .user(promptContent)
                // 会话 ID 经上下文传给 MessageChatMemoryAdvisor，自动注入历史并持久化本轮对话
                .advisors(advisorSpec -> advisorSpec.param(ChatMemory.CONVERSATION_ID, String.valueOf(conversationId)))
                .stream()
                .chatResponse()
                // 空文本 chunk 过滤（工具调用 chunk 的防御性处理）
                .filter(resp -> resp.getResult() != null
                        && resp.getResult().getOutput() != null
                        && StringUtils.hasText(resp.getResult().getOutput().getText()))
                .map(resp -> ChatEvent.delta(turnId, resp.getResult().getOutput().getText()))
                .doOnNext(e -> answer.append(e.getText()))
                // 回合 COMPLETE 前同步落库：事实源先持久化，再向用户宣告结束
                .doOnComplete(() -> {
                    Message assistantMessage = new Message()
                            .setConversationId(conversationId)
                            .setRole("assistant")
                            .setMsgType("text")
                            .setContent(answer.toString())
                            .setCreatedAt(LocalDateTime.now());
                    messageMapper.insert(assistantMessage);
                    savedMessageId[0] = assistantMessage.getId();
                    touchConversation(conversationId);
                })
                .onErrorResume(ex -> {
                    log.error("LLM 流式调用失败, conversationId={}", conversationId, ex);
                    return Flux.just(ChatEvent.error(turnId, "AI_ERROR", "生成失败，请稍后重试"));
                });

        // 正常路径：COMPLETE(messageId) + STOP；错误路径：仅 STOP
        return body.concatWith(Flux.defer(() -> {
            List<ChatEvent> tail = new java.util.ArrayList<>();
            if (savedMessageId[0] > 0) {
                tail.add(ChatEvent.complete(turnId, savedMessageId[0]));
            }
            tail.add(ChatEvent.stop(turnId));
            return Flux.fromIterable(tail);
        }));
    }

    private void touchConversation(Long conversationId) {
        Conversation conversation = new Conversation()
                .setId(conversationId)
                .setLastActiveAt(LocalDateTime.now());
        conversationMapper.updateById(conversation);
    }
}
