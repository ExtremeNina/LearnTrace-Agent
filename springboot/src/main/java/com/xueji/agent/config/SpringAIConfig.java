package com.xueji.agent.config;

import com.xueji.agent.ai.memory.RedisChatMemoryRepository;
import com.xueji.agent.ai.tool.OcrTool;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.SimpleLoggerAdvisor;
import org.springframework.ai.chat.client.advisor.api.Advisor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.memory.ChatMemoryRepository;
import org.springframework.ai.chat.memory.MessageWindowChatMemory;
import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * Spring AI 装配：LLM 调用统一经 ChatClient（PRD §11，业务代码不直接调 LLM API）。
 * 记忆链路：RedisChatMemoryRepository（Redis 存储）→ MessageWindowChatMemory（滑窗）
 * → MessageChatMemoryAdvisor（自动读写记忆，随 ChatClient 全局生效）。
 * 工具链路：OcrTool（阿里云 OCR）注册为默认工具，模型按需调用。
 */
@Configuration
public class SpringAIConfig {

    /**
     * 记忆窗口上限：最多保存 100 条消息，超出自动淘汰最旧的
     */
    @Value("${xj.agent.memory.max-messages:100}")
    private int maxMessages;

    @Value("${aliyun.ocr.access-key:}")
    private String ocrAccessKey;

    @Value("${aliyun.ocr.secret-key:}")
    private String ocrSecretKey;

    @Value("${aliyun.ocr.endpoint:ocr-api.cn-hangzhou.aliyuncs.com}")
    private String ocrEndpoint;

    @Bean
    public ChatMemoryRepository redisChatMemoryRepository(StringRedisTemplate stringRedisTemplate) {
        return new RedisChatMemoryRepository(stringRedisTemplate);
    }

    @Bean
    public ChatMemory chatMemory(ChatMemoryRepository chatMemoryRepository) {
        // 基于 chatMemoryRepository 对象构建 chatMemory 对象
        return MessageWindowChatMemory.builder()
                .chatMemoryRepository(chatMemoryRepository)
                .maxMessages(this.maxMessages)
                .build();
    }

    /**
     * 基于 Redis 的会话记忆，聊天记忆整合到 message 列表中实现多轮对话
     */
    @Bean
    public Advisor messageChatMemoryAdvisor(ChatMemory chatMemory) {
        // 创建基于 chatMemory 的 Advisor 对象
        return MessageChatMemoryAdvisor.builder(chatMemory).build();
    }

    @Bean
    public Advisor loggerAdvisor() {
        return new SimpleLoggerAdvisor();
    }

    /**
     * OCR 工具：题目识别（AI Tool，模型按需调用）
     */
    @Bean
    public OcrTool ocrTool() {
        return new OcrTool(ocrAccessKey, ocrSecretKey, ocrEndpoint);
    }

    @Bean
    public ChatClient chatClient(ChatClient.Builder chatClientBuilder,
                                 Advisor messageChatMemoryAdvisor,
                                 Advisor loggerAdvisor,
                                 OcrTool ocrTool) {
        return chatClientBuilder
                .defaultAdvisors(messageChatMemoryAdvisor, loggerAdvisor)
                .defaultTools(ocrTool)
                .build();
    }
}
