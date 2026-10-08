package com.xueji.agent.domain.vo;

import lombok.Getter;

/**
 * Agent 回合事件（WS 下行，协议见对话设计）：
 * DELTA / COMPLETE / ERROR / STOP 为回合事件；
 * TRANSCRIBE 为视频转写进度与结果事件（后台任务主动推送，messageId = 转写占位消息）。
 */
@Getter
public class ChatEvent {

    private final String type;
    private final String turnId;
    private final String text;
    private final Long messageId;
    private final String code;
    private final String message;
    /** 仅 TRANSCRIBE：任务状态 processing / done / failed */
    private final String status;
    /** 仅 TRANSCRIBE：已完成分片数 */
    private final Integer done;
    /** 仅 TRANSCRIBE：总分片数 */
    private final Integer total;
    /** 仅 COMPLETE：消息 payload JSON（意图确认大卡片等结构化扩展随消息透传） */
    private final String payload;

    private ChatEvent(String type, String turnId, String text, Long messageId, String code, String message,
                      String status, Integer done, Integer total) {
        this(type, turnId, text, messageId, code, message, status, done, total, null);
    }

    private ChatEvent(String type, String turnId, String text, Long messageId, String code, String message,
                      String status, Integer done, Integer total, String payload) {
        this.type = type;
        this.turnId = turnId;
        this.text = text;
        this.messageId = messageId;
        this.code = code;
        this.message = message;
        this.status = status;
        this.done = done;
        this.total = total;
        this.payload = payload;
    }

    private ChatEvent(String type, String turnId, String text, Long messageId, String code, String message) {
        this(type, turnId, text, messageId, code, message, null, null, null, null);
    }

    public static ChatEvent delta(String turnId, String text) {
        return new ChatEvent("DELTA", turnId, text, null, null, null);
    }

    public static ChatEvent complete(String turnId, Long messageId) {
        return new ChatEvent("COMPLETE", turnId, null, messageId, null, null);
    }

    /** COMPLETE 变体：随消息透出 payload（意图确认大卡片） */
    public static ChatEvent complete(String turnId, Long messageId, String payload) {
        return new ChatEvent("COMPLETE", turnId, null, messageId, null, null, null, null, null, payload);
    }

    public static ChatEvent error(String turnId, String code, String message) {
        return new ChatEvent("ERROR", turnId, null, null, code, message);
    }

    public static ChatEvent stop(String turnId) {
        return new ChatEvent("STOP", turnId, null, null, null, null);
    }

    /** 视频转写进度 / 结果事件（后台任务推送，text = 完成时的转写全文） */
    public static ChatEvent transcribe(Long messageId, String status, String text, Integer done, Integer total, String errorMessage) {
        return new ChatEvent("TRANSCRIBE", null, text, messageId, null, errorMessage, status, done, total);
    }

    /** 课程流水线阶段进度事件（B11 分流，后台推送；message = 完成时的课程链接路径 / 失败原因） */
    public static ChatEvent course(Long messageId, String stage, String text, String extra) {
        return new ChatEvent("COURSE", null, text, messageId, null, extra, stage, null, null);
    }
}
