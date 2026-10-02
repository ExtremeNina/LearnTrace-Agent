package com.xueji.agent.common;

/**
 * Redis 键名统一收口
 */
public final class RedisKeys {

    private RedisKeys() {
    }

    /** Agent 会话记忆（Spring AI ChatMemory，按会话 ID 分键） */
    public static final String CHAT_MEMORY_PREFIX = "xj:chat:memory:";

    /** RAG 向量化补漏定时任务的水位（ISO 时间字符串，缺失表示全量回填）。
     * v2：文档 ID 引入 q:/sq: 前缀后升版，键变更即触发一次全量重摄 */
    public static final String RAG_REPAIR_CHECKPOINT = "xj:rag:repair:checkpoint:v2";
}
