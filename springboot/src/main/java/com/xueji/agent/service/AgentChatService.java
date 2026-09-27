package com.xueji.agent.service;

import com.xueji.agent.domain.vo.ChatEvent;
import reactor.core.publisher.Flux;

/**
 * Agent 对话：多轮流式对话（记忆 = 会话历史消息参与上下文）
 */
public interface AgentChatService {

    /**
     * 发起一个回合：持久化用户消息 → 带历史上下文流式调用 LLM → 持久化回复。
     * 事件序列：DELTA*（+ COMPLETE）+ STOP（任何路径都以 STOP 结尾）
     */
    Flux<ChatEvent> chat(Long userId, Long conversationId, String content);
}
