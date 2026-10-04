package com.xueji.agent.ws;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.xueji.agent.domain.vo.ChatEvent;
import com.xueji.agent.exception.BusinessException;
import com.xueji.agent.service.AgentChatService;
import com.xueji.agent.service.ConversationService;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;
import reactor.core.Disposable;

import java.io.IOException;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Agent WebSocket 处理器：
 * 上行 chat.send / chat.stop；下行 ChatEvent JSON。
 * 回合互斥：同一会话同时只允许一个回合（内存守卫，单实例部署；分布式锁 Redisson 后置）。
 */
@Slf4j
@Component
public class AgentWebSocketHandler extends TextWebSocketHandler {

    @Resource
    private AgentChatService agentChatService;

    @Resource
    private ConversationService conversationService;

    @Resource
    private AgentEventPushService pushService;

    @Resource
    private ObjectMapper objectMapper;

    /** 回合互斥守卫：conversationId -> turnId */
    private final Map<Long, String> activeTurns = new ConcurrentHashMap<>();

    /** 进行中的订阅：conversationId -> Disposable（打断用） */
    private final Map<Long, Disposable> runningTurns = new ConcurrentHashMap<>();

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) throws IOException {
        JsonNode node = objectMapper.readTree(message.getPayload());
        String type = node.path("type").asText("");
        Long userId = (Long) session.getAttributes().get("userId");
        if (userId == null) {
            sendEvent(session, ChatEvent.error(null, "AUTH_ERROR", "登录信息失效"));
            return;
        }

        switch (type) {
            case "chat.send" -> handleSend(session, userId, node);
            case "chat.stop" -> handleStop(session, node);
            default -> sendEvent(session, ChatEvent.error(null, "BAD_REQUEST", "未知的消息类型"));
        }
    }

    private void handleSend(WebSocketSession session, Long userId, JsonNode node) throws IOException {
        long conversationId = node.path("conversationId").asLong();
        String content = node.path("content").asText("").trim();
        if (content.isEmpty()) {
            sendEvent(session, ChatEvent.error(null, "BAD_REQUEST", "消息不能为空"));
            return;
        }

        // 会话轮次上限：超过后拒绝继续对话，引导新建会话
        if (conversationService.countUserMessages(userId, conversationId) >= ConversationService.MAX_TURNS_PER_CONVERSATION) {
            sendEvent(session, ChatEvent.error(null, "CONVERSATION_LIMIT", "该会话对话已达 100 次上限，请新建会话继续"));
            return;
        }

        String turnId = "t_" + UUID.randomUUID().toString().substring(0, 8);

        // 回合互斥：第二个回合直接拒绝，不排队
        String existing = activeTurns.putIfAbsent(conversationId, turnId);
        if (existing != null) {
            sendEvent(session, ChatEvent.error(turnId, "TURN_IN_PROGRESS", "当前会话正在回复中"));
            return;
        }

        String imageUrl = node.path("imageUrl").asText("");
        // B11 视频消息：上传接口产出的本地临时路径与 ffprobe 时长（存在时走视频转写链路）
        String videoTempPath = node.path("videoTempPath").asText("");
        Integer videoDurationSec = node.has("videoDurationSec") && node.path("videoDurationSec").isInt()
                ? node.path("videoDurationSec").asInt() : null;
        Disposable disposable = agentChatService.chat(userId, conversationId, content, imageUrl, videoTempPath, videoDurationSec)
                .doFinally(sig -> {
                    // 服务端事件流已包含 STOP，这里只做守卫清理
                    activeTurns.remove(conversationId);
                    runningTurns.remove(conversationId);
                })
                .subscribe(
                        event -> {
                            try {
                                sendEvent(session, event);
                            } catch (IOException e) {
                                log.warn("WS 发送失败，取消回合", e);
                                Disposable d = runningTurns.remove(conversationId);
                                if (d != null) {
                                    d.dispose();
                                }
                            }
                        },
                        err -> log.error("回合异常结束, conversationId={}", conversationId, err)
                );
        runningTurns.put(conversationId, disposable);
    }

    private void handleStop(WebSocketSession session, JsonNode node) throws IOException {
        long conversationId = node.path("conversationId").asLong();
        Disposable disposable = runningTurns.remove(conversationId);
        if (disposable != null) {
            disposable.dispose();
            activeTurns.remove(conversationId);
            sendEvent(session, ChatEvent.stop(null));
        }
    }

    private synchronized void sendEvent(WebSocketSession session, ChatEvent event) throws IOException {
        if (session.isOpen()) {
            session.sendMessage(new TextMessage(objectMapper.writeValueAsString(event)));
        }
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession session) {
        // 注册 userId → session：后台任务（视频转写等）完成后主动推送事件回流对话
        Long userId = (Long) session.getAttributes().get("userId");
        if (userId != null) {
            pushService.register(userId, session);
        }
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        // 连接断开不取消进行中的回合：服务端继续跑完并落库，前端重连后从 REST 补齐
        pushService.unregister(session);
    }
}
