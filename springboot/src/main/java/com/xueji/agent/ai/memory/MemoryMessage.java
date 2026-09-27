package com.xueji.agent.ai.memory;

/**
 * Redis 记忆存储的持久化形态：只保留角色与文本，
 * 与 Spring AI 的 Message 类型互转在 RedisChatMemoryRepository 内完成
 */
public class MemoryMessage {

    private String role;

    private String text;

    public MemoryMessage() {
    }

    public MemoryMessage(String role, String text) {
        this.role = role;
        this.text = text;
    }

    public String getRole() {
        return role;
    }

    public void setRole(String role) {
        this.role = role;
    }

    public String getText() {
        return text;
    }

    public void setText(String text) {
        this.text = text;
    }
}
