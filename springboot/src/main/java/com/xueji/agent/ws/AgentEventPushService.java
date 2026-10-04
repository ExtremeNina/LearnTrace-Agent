package com.xueji.agent.ws;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.xueji.agent.domain.vo.ChatEvent;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArraySet;

/**
 * WS 主动推送服务：userId → session 注册表（后台任务完成 / 进度事件回流对话的关键基建）。
 * 连接建立 / 断开由 AgentWebSocketHandler 回调维护；推送对离线用户静默跳过
 * （结果已持久化到消息表，前端重进会话从 REST 补齐）。
 */
@Slf4j
@Component
public class AgentEventPushService {

    @Resource
    private ObjectMapper objectMapper;

    /** 在线注册表：userId -> 该用户的全部活跃连接（多标签页） */
    private final Map<Long, Set<WebSocketSession>> userSessions = new ConcurrentHashMap<>();

    public void register(Long userId, WebSocketSession session) {
        userSessions.computeIfAbsent(userId, k -> new CopyOnWriteArraySet<>()).add(session);
    }

    public void unregister(WebSocketSession session) {
        for (Set<WebSocketSession> sessions : userSessions.values()) {
            sessions.remove(session);
        }
    }

    /** 向用户全部活跃连接推送事件；发送失败仅记录，不抛出 */
    public void pushToUser(Long userId, ChatEvent event) {
        Set<WebSocketSession> sessions = userSessions.get(userId);
        if (sessions == null || sessions.isEmpty()) {
            log.info("推送时用户不在线（结果已落库）, userId={}, type={}, messageId={}",
                    userId, event.getType(), event.getMessageId());
            return;
        }
        List<WebSocketSession> dead = new ArrayList<>();
        for (WebSocketSession session : sessions) {
            try {
                if (session.isOpen()) {
                    synchronized (this) {
                        session.sendMessage(new TextMessage(objectMapper.writeValueAsString(event)));
                    }
                } else {
                    dead.add(session);
                }
            } catch (IOException e) {
                log.warn("WS 推送失败, userId={}, type={}", userId, event.getType(), e);
                dead.add(session);
            }
        }
        for (WebSocketSession session : dead) {
            sessions.remove(session);
        }
    }
}
