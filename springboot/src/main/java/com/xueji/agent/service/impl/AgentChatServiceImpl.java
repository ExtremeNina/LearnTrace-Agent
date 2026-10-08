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

    @Resource
    private com.xueji.agent.service.ProfileService profileService;

    @Resource
    private com.xueji.agent.ai.IntentAgentService intentAgentService;

    @Override
    public Flux<ChatEvent> chat(Long userId, Long conversationId, String content, String imageUrl,
                                String videoTempPath, Integer videoDurationSec) {
        OwnershipCheck.requireOwned(conversationMapper.selectById(conversationId), userId, "会话不存在");

        String turnId = "t_" + java.util.UUID.randomUUID().toString().substring(0, 8);

        // 带图先走前置流水线（OCR 识别 → 格式化），结果随消息落库并拼入 prompt；
        // 视频消息（B11）优先：转写由后台任务异步执行，不进图片解题链路
        boolean hasVideo = videoTempPath != null && !videoTempPath.isBlank() && videoDurationSec != null;
        boolean hasImage = !hasVideo && imageUrl != null && !imageUrl.isBlank();

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
                    .setMsgType(hasVideo ? "video" : "text")
                    .setContent(content)
                    .setCreatedAt(LocalDateTime.now());
            if (hasImage) {
                cn.hutool.json.JSONObject payload = cn.hutool.json.JSONUtil.createObj().set("imageUrl", imageUrl);
                if (questionText != null && !questionText.isBlank()) {
                    payload.set("questionText", questionText);
                }
                userMessage.setPayload(payload.toString());
            }
            if (hasVideo) {
                // 视频元信息入 payload（前端历史渲染展示；videoTempPath 供后续回合转写工具回查，
                // 画像问询等打断场景下 ToolContext 只在首个回合有效）；OSS 地址由转写任务上传后回填
                cn.hutool.json.JSONObject payload = cn.hutool.json.JSONUtil.createObj()
                        .set("videoDurationSec", videoDurationSec)
                        .set("videoTempPath", videoTempPath);
                userMessage.setPayload(payload.toString());
            }
            messageMapper.insert(userMessage);

            // 会话仍是默认标题时，用首条用户消息生成可区分的标题（重名自动加序号）
            conversationService.applyTitleFromFirstMessage(userId, conversationId, content);

            // 意图 Agent 分流（B27，独立于主 LLM）：
            // ① 带视频回合 → askIntent 按用户输入定制提问 + 意图选项卡片，不走主 LLM 工具链
            // ② 文本回合且有 pending 意图 → resolveAndExecute 解析回答、画像落档并直接触发转写/建课
            if (hasVideo) {
                com.xueji.agent.ai.IntentAgentService.AskResult ask =
                        intentAgentService.askIntent(userId, conversationId, content, videoTempPath, videoDurationSec);
                return staticReply(turnId, conversationId, ask.text(), ask.intentCardJson());
            }
            if (!hasImage && StringUtils.hasText(content) && intentAgentService.hasPending(conversationId)) {
                String reply = intentAgentService.resolveAndExecute(userId, conversationId, content);
                if (reply != null) {
                    return staticReply(turnId, conversationId, reply);
                }
            }

            // DeepSeek 平台 API 初期为纯文本（PRD §11 不引入多模态）：题目文本以文字形式拼入 prompt
            String promptContent = content;
            if (hasVideo) {
                int min = videoDurationSec / 60;
                int sec = videoDurationSec % 60;
                promptContent = content + String.format(
                        "\n\n[系统提示：用户上传了一个视频（时长 %02d:%02d），已就绪可转写。若用户意图与视频内容相关，请调用 transcribeVideo 工具提交转写任务]",
                        min, sec);
            } else if (hasImage) {
                if (questionText == null || questionText.isBlank()) {
                    promptContent = content + "\n\n[系统提示：题目图片识别失败或超时，请提示用户检查图片是否清晰可读并重新上传，或直接输入题目文字，不要猜测题目内容]";
                } else {
                    promptContent = content + "\n\n[题目图片识别文本（已整理）]\n" + questionText;
                }
            }

            StringBuilder answer = new StringBuilder();
            long[] savedMessageId = new long[1];

            // 按场景选择系统提示词：带视频走转写流程，带图走解题流程，否则用基础人设；
            // 末尾注入学习者画像块（有画像个性化回答，未填写附问询采集指引，B26 反馈）
            String basePrompt = hasVideo ? AgentPrompts.VIDEO_PROMPT
                    : hasImage ? AgentPrompts.QUESTION_PROMPT : AgentPrompts.BASE_PROMPT;
            com.xueji.agent.domain.entity.UserProfile learnerProfile = profileService.getByUser(userId);
            String systemPrompt = basePrompt + AgentPrompts.profileContext(
                    com.xueji.agent.ai.ContentReviewService.profileText(learnerProfile), learnerProfile != null);

            // 按用户模块偏好解析当前对话模型（默认回退系统 DeepSeek）
        ChatClient chatClient = aiModelService.resolve(userId, AiModelService.MODULE_CHAT, ChatClientFactory.Variant.CHAT);

            // 工具上下文：userId / conversationId 固定传；视频消息附带临时文件与时长供转写工具取用
            java.util.Map<String, Object> toolContext = new java.util.HashMap<>();
            toolContext.put("userId", userId);
            toolContext.put("conversationId", conversationId);
            if (hasVideo) {
                toolContext.put("videoTempPath", videoTempPath);
                toolContext.put("videoDurationSec", videoDurationSec);
            }

        Flux<ChatEvent> body = chatClient.prompt()
                    .system(systemPrompt)
                    .user(promptContent)
                    // 会话 ID 经上下文传给 MessageChatMemoryAdvisor，自动注入历史并持久化本轮对话
                    .advisors(advisorSpec -> advisorSpec.param(ChatMemory.CONVERSATION_ID, String.valueOf(conversationId)))
                    // userId / conversationId 经工具上下文传给保存题目等工具（工具自身不感知会话）
                    .toolContext(toolContext)
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

    /** 意图 Agent 等非流式回复：整段一次性 DELTA + 落库 + COMPLETE/STOP（复用主链路的前端事件协议）；payloadJson 非空时随消息落库（前端大卡片渲染） */
    private Flux<ChatEvent> staticReply(String turnId, Long conversationId, String text, String payloadJson) {
        return Flux.defer(() -> {
            Message assistantMessage = new Message()
                    .setConversationId(conversationId)
                    .setRole("assistant")
                    .setMsgType("text")
                    .setContent(text)
                    .setCreatedAt(LocalDateTime.now());
            if (payloadJson != null && !payloadJson.isBlank()) {
                assistantMessage.setPayload(payloadJson);
            }
            messageMapper.insert(assistantMessage);
            touchConversation(conversationId);
            return Flux.just(
                    ChatEvent.delta(turnId, text),
                    ChatEvent.complete(turnId, assistantMessage.getId(), payloadJson),
                    ChatEvent.stop(turnId));
        });
    }

    private Flux<ChatEvent> staticReply(String turnId, Long conversationId, String text) {
        return staticReply(turnId, conversationId, text, null);
    }

    private void touchConversation(Long conversationId) {
        Conversation conversation = new Conversation()
                .setId(conversationId)
                .setLastActiveAt(LocalDateTime.now());
        conversationMapper.updateById(conversation);
    }
}
