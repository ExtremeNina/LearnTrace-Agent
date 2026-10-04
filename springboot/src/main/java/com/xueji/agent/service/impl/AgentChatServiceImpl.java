package com.xueji.agent.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.xueji.agent.ai.prompt.AgentPrompts;
import com.xueji.agent.ai.tool.OcrTextFormatter;
import com.xueji.agent.ai.tool.OcrTool;
import com.xueji.agent.common.OwnershipCheck;
import com.xueji.agent.domain.entity.Conversation;
import com.xueji.agent.domain.entity.Message;
import com.xueji.agent.domain.vo.ChatEvent;
import com.xueji.agent.mapper.ConversationMapper;
import com.xueji.agent.mapper.MessageMapper;
import com.xueji.agent.config.ChatClientFactory;
import com.xueji.agent.service.AgentChatService;
import com.xueji.agent.service.AiModelService;
import com.xueji.agent.service.ConversationService;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import reactor.core.publisher.Flux;
import reactor.core.scheduler.Schedulers;

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
    private AiModelService aiModelService;

    @Resource
    private ConversationMapper conversationMapper;

    @Resource
    private MessageMapper messageMapper;

    @Resource
    private ConversationService conversationService;

    @Resource
    private OcrTool ocrTool;

    @Resource
    private OcrTextFormatter ocrTextFormatter;

    @Override
    public Flux<ChatEvent> chat(Long userId, Long conversationId, String content, String imageUrl) {
        OwnershipCheck.requireOwned(conversationMapper.selectById(conversationId), userId, "会话不存在");

        String turnId = "t_" + java.util.UUID.randomUUID().toString().substring(0, 8);

        // 带图先走前置流水线（OCR 识别 → 格式化），结果随消息落库并拼入 prompt
        boolean hasImage = imageUrl != null && !imageUrl.isBlank();

        // 回合整体延迟到订阅期执行并切到 boundedElastic：PaddleOCR 为轮询式长任务（上限可配，默认 45s），
        // 不得阻塞 WS / 请求线程（PRD §16 长耗时任务不阻塞请求）
        return Flux.defer(() -> {
            String questionText = null;
            if (hasImage) {
                try {
                    questionText = ocrTextFormatter.format(ocrTool.recognizeText(imageUrl));
                } catch (Exception e) {
                    log.error("OCR 前置流水线失败, imageUrl={}", imageUrl, e);
                }
            }

            // 用户消息先落事实源（回合中途崩溃也不丢用户输入）；附图链接与题目识别文本记入 payload（保存题目时取用）
            Message userMessage = new Message()
                    .setConversationId(conversationId)
                    .setRole("user")
                    .setMsgType("text")
                    .setContent(content)
                    .setCreatedAt(LocalDateTime.now());
            if (hasImage) {
                cn.hutool.json.JSONObject payload = cn.hutool.json.JSONUtil.createObj().set("imageUrl", imageUrl);
                if (questionText != null && !questionText.isBlank()) {
                    payload.set("questionText", questionText);
                }
                userMessage.setPayload(payload.toString());
            }
            messageMapper.insert(userMessage);

            // 会话仍是默认标题时，用首条用户消息生成可区分的标题（重名自动加序号）
            conversationService.applyTitleFromFirstMessage(userId, conversationId, content);

            // DeepSeek 平台 API 初期为纯文本（PRD §11 不引入多模态）：题目文本以文字形式拼入 prompt
            String promptContent = content;
            if (hasImage) {
                if (questionText == null || questionText.isBlank()) {
                    promptContent = content + "\n\n[系统提示：题目图片识别失败或超时，请提示用户检查图片是否清晰可读并重新上传，或直接输入题目文字，不要猜测题目内容]";
                } else {
                    promptContent = content + "\n\n[题目图片识别文本（已整理）]\n" + questionText;
                }
            }

            StringBuilder answer = new StringBuilder();
            long[] savedMessageId = new long[1];

            // 按场景选择系统提示词：带图走解题流程，否则用基础人设
            String systemPrompt = hasImage ? AgentPrompts.QUESTION_PROMPT : AgentPrompts.BASE_PROMPT;

            // 按用户模块偏好解析当前对话模型（默认回退系统 DeepSeek）
        ChatClient chatClient = aiModelService.resolve(userId, AiModelService.MODULE_CHAT, ChatClientFactory.Variant.CHAT);

        Flux<ChatEvent> body = chatClient.prompt()
                    .system(systemPrompt)
                    .user(promptContent)
                    // 会话 ID 经上下文传给 MessageChatMemoryAdvisor，自动注入历史并持久化本轮对话
                    .advisors(advisorSpec -> advisorSpec.param(ChatMemory.CONVERSATION_ID, String.valueOf(conversationId)))
                    // userId / conversationId 经工具上下文传给保存题目等工具（工具自身不感知会话）
                    .toolContext(java.util.Map.of("userId", userId, "conversationId", conversationId))
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
        }).subscribeOn(Schedulers.boundedElastic());
    }

    private void touchConversation(Long conversationId) {
        Conversation conversation = new Conversation()
                .setId(conversationId)
                .setLastActiveAt(LocalDateTime.now());
        conversationMapper.updateById(conversation);
    }
}
