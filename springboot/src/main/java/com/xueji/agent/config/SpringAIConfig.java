package com.xueji.agent.config;

import com.xueji.agent.ai.memory.RedisChatMemoryRepository;
import com.xueji.agent.ai.tool.AliyunOcrTool;
import com.xueji.agent.ai.tool.OcrTool;
import com.xueji.agent.ai.tool.PaddleOcrTool;
import com.xueji.agent.ai.tool.QwenAsrTool;
import com.xueji.agent.ai.tool.QuestionSaveTool;
import com.xueji.agent.ai.tool.RagSearchTool;
import com.xueji.agent.service.QuestionService;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.SimpleLoggerAdvisor;
import org.springframework.ai.chat.client.advisor.api.Advisor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.memory.ChatMemoryRepository;
import org.springframework.ai.chat.memory.MessageWindowChatMemory;
import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
import org.springframework.ai.document.MetadataMode;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.openai.OpenAiEmbeddingModel;
import org.springframework.ai.openai.OpenAiEmbeddingOptions;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.redis.RedisVectorStore;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.data.redis.core.StringRedisTemplate;
import redis.clients.jedis.JedisPooled;

/**
 * Spring AI 装配：LLM 调用统一经 ChatClient（PRD §11，业务代码不直接调 LLM API）。
 * 记忆链路：RedisChatMemoryRepository（Redis 存储）→ MessageWindowChatMemory（滑窗）
 * → MessageChatMemoryAdvisor（自动读写记忆，随 ChatClient 全局生效）。
 * 工具链路：OCR 已改为前置流水线（业务代码先识别 + OcrTextFormatter 整理，再进推理），
 * ChatClient 不再注册 AI Tool。
 */
@Slf4j
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

    @Value("${paddle-ocr.api-base:https://paddleocr.aistudio-app.com/api/v2/ocr}")
    private String paddleApiBase;

    @Value("${paddle-ocr.token:}")
    private String paddleToken;

    @Value("${paddle-ocr.model:PaddleOCR-VL-1.6}")
    private String paddleModel;

    @Value("${qwen.asr.api-key:}")
    private String qwenAsrApiKey;

    @Value("${qwen.asr.base-url:https://ws-swm6f3vt0izc1plg.cn-beijing.maas.aliyuncs.com}")
    private String qwenAsrBaseUrl;

    @Value("${qwen.asr.model:qwen-audio-3.1-asr-flash}")
    private String qwenAsrModel;

    @Value("${qwen.asr.format:wav}")
    private String qwenAsrFormat;

    @Value("${qwen.asr.sample-rate:16000}")
    private String qwenAsrSampleRate;

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
     * OCR 识别端（前置流水线使用）：当前选用百度 PaddleOCR（PaddleOCR-VL，
     * 输出结构化 Markdown，复杂版面 / 试卷还原好）。
     */
    @Bean
    public OcrTool ocrTool() {
        return new PaddleOcrTool(paddleApiBase, paddleToken, paddleModel);
    }

    /**
     * 阿里云读光 OCR 备选实现（快速纯文本），可通过切换 Bean 启用
     */
    @Bean
    public OcrTool aliyunOcrTool() {
        return new AliyunOcrTool(ocrAccessKey, ocrSecretKey, ocrEndpoint);
    }

    /**
     * 语音转写工具：Qwen-Audio ASR-Flash（网课流水线音频通道，前置流水线调用）
     */
    @Bean
    public QwenAsrTool qwenAsrTool() {
        return new QwenAsrTool(qwenAsrBaseUrl, qwenAsrApiKey, qwenAsrModel, qwenAsrFormat, qwenAsrSampleRate);
    }

    /**
     * 保存题目工具：注册为默认工具，用户表达保存意图时由模型调用（userId 经 ToolContext 传入）
     */
    @Bean
    public QuestionSaveTool questionSaveTool(QuestionService questionService) {
        return new QuestionSaveTool(questionService);
    }

    @Bean
    @Primary
    public EmbeddingModel embeddingModel(
            @Value("${xj.embedding.base-url:https://dashscope.aliyuncs.com/compatible-mode/v1}") String baseUrl,
            @Value("${xj.embedding.api-key:}") String apiKey,
            @Value("${xj.embedding.model:text-embedding-v3}") String model,
            @Value("${qwen.asr.api-key:}") String asrKeyForProbe) {
        // Embedding 走百炼 OpenAI 兼容端点（DeepSeek 无 embedding 接口）；
        // 标记 @Primary 覆盖 openai starter 按 spring.ai.openai.* 自动装配的默认实例
        // 百炼兼容端点已含 /v1，覆盖默认路径避免 /v1/v1 叠加 404
        log.info("Embedding 探针: xj key 长度={}, qwen key 长度={}, base-url={}", apiKey.length(), asrKeyForProbe.length(), baseUrl);
        OpenAiApi api = OpenAiApi.builder()
                .baseUrl(baseUrl)
                .apiKey(apiKey)
                .embeddingsPath("/embeddings")
                .build();
        return new OpenAiEmbeddingModel(api, MetadataMode.EMBED,
                OpenAiEmbeddingOptions.builder().model(model).build());
    }

    /**
     * 向量库：独立 Redis 实例（6380，redis-stack），仅存 RAG 向量与元数据；
     * 索引启动时自动创建（FT.CREATE），维度由 embedding 模型决定（text-embedding-v3 = 1024）
     */
    @Bean
    public VectorStore questionVectorStore(EmbeddingModel embeddingModel,
            @Value("${xj.vector.redis.host:127.0.0.1}") String host,
            @Value("${xj.vector.redis.port:6380}") int port) {
        JedisPooled jedis = new JedisPooled(host, port);
        return RedisVectorStore.builder(jedis, embeddingModel)
                .indexName("xueji-rag-idx")
                .prefix("rag:question:")
                .metadataFields(
                        RedisVectorStore.MetadataField.tag("userId"),
                        RedisVectorStore.MetadataField.tag("subject"))
                .initializeSchema(true)
                .build();
    }

    @Bean
    public RagSearchTool ragSearchTool(VectorStore questionVectorStore) {
        return new RagSearchTool(questionVectorStore);
    }

    @Bean
    public ChatClient chatClient(ChatClient.Builder chatClientBuilder,
                                 Advisor messageChatMemoryAdvisor,
                                 Advisor loggerAdvisor,
                                 QuestionSaveTool questionSaveTool,
                                 RagSearchTool ragSearchTool) {
        return chatClientBuilder
                .defaultAdvisors(messageChatMemoryAdvisor, loggerAdvisor)
                .defaultTools(questionSaveTool, ragSearchTool)
                .build();
    }
}
