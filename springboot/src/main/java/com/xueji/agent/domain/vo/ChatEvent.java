package com.xueji.agent.domain.vo;

import lombok.Getter;

/**
 * Agent 回合事件（WS 下行，协议见对话设计）：DELTA / COMPLETE / ERROR / STOP
 */
@Getter
public class ChatEvent {

    private final String type;
    private final String turnId;
    private final String text;
    private final Long messageId;
    private final String code;
    private final String message;

    private ChatEvent(String type, String turnId, String text, Long messageId, String code, String message) {
        this.type = type;
        this.turnId = turnId;
        this.text = text;
        this.messageId = messageId;
        this.code = code;
        this.message = message;
    }

    public static ChatEvent delta(String turnId, String text) {
        return new ChatEvent("DELTA", turnId, text, null, null, null);
    }

    public static ChatEvent complete(String turnId, Long messageId) {
        return new ChatEvent("COMPLETE", turnId, null, messageId, null, null);
    }

    public static ChatEvent error(String turnId, String code, String message) {
        return new ChatEvent("ERROR", turnId, null, null, code, message);
    }

    public static ChatEvent stop(String turnId) {
        return new ChatEvent("STOP", turnId, null, null, null, null);
    }
}
