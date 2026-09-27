package com.xueji.agent.common;

/**
 * Redis 键名统一收口
 */
public final class RedisKeys {

    private RedisKeys() {
    }

    /** Agent 会话记忆（Spring AI ChatMemory，按会话 ID 分键） */
    public static final String CHAT_MEMORY_PREFIX = "xj:chat:memory:";
}
