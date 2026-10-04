package com.xueji.agent.config;

import com.xueji.agent.ai.tool.LearningStatusTool;
import com.xueji.agent.ai.tool.QuestionSaveTool;
import com.xueji.agent.ai.tool.RagSearchTool;
import com.xueji.agent.domain.entity.AiModelConfig;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.api.Advisor;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 按用户自建模型配置构建并缓存 ChatClient（DB 驱动）。
 * 两种形态：CHAT（记忆 + 对话工具，供对话回合）/ GENERATION（裸，供笔记 / 简报等一次性生成）。
 * 同一配置同形态复用同一实例；配置变更 / 删除时调用 invalidate 使缓存失效。
 * 构建期不发网络请求（仅保存协议配置），调用期才真正访问供应商。
 */
@Component
public class ChatClientFactory {

    public enum Variant {
        /** 对话形态：记忆 + 对话工具 */
        CHAT,
        /** 生成形态：无工具无记忆（一次性生成任务） */
        GENERATION
    }

    private final Map<String, ChatClient> cache = new ConcurrentHashMap<>();

    private final Advisor chatMemoryAdvisor;
    private final Advisor loggerAdvisor;
    private final QuestionSaveTool questionSaveTool;
    private final RagSearchTool ragSearchTool;
    private final LearningStatusTool learningStatusTool;

    public ChatClientFactory(@Qualifier("messageChatMemoryAdvisor") Advisor chatMemoryAdvisor,
                             @Qualifier("loggerAdvisor") Advisor loggerAdvisor,
                             QuestionSaveTool questionSaveTool,
                             RagSearchTool ragSearchTool,
                             LearningStatusTool learningStatusTool) {
        this.chatMemoryAdvisor = chatMemoryAdvisor;
        this.loggerAdvisor = loggerAdvisor;
        this.questionSaveTool = questionSaveTool;
        this.ragSearchTool = ragSearchTool;
        this.learningStatusTool = learningStatusTool;
    }

    /** 对话形态客户端（带记忆与工具） */
    public ChatClient getChatClient(AiModelConfig config) {
        return get(config, Variant.CHAT);
    }

    /** 生成形态客户端（无工具无记忆） */
    public ChatClient getGenerationClient(AiModelConfig config) {
        return get(config, Variant.GENERATION);
    }

    private ChatClient get(AiModelConfig config, Variant variant) {
        return cache.computeIfAbsent(cacheKey(config.getId(), variant), key -> build(config, variant));
    }

    /** 配置变更 / 删除时使该配置的全部缓存失效 */
    public void invalidate(Long configId) {
        cache.keySet().removeIf(key -> key.startsWith(configId + ":"));
    }

    /** 临时构建（不入缓存）：测试连接用 */
    public ChatClient buildTransient(AiModelConfig config) {
        return build(config, Variant.GENERATION);
    }

    private String cacheKey(Long configId, Variant variant) {
        return configId + ":" + variant;
    }

    private ChatClient build(AiModelConfig config, Variant variant) {
        OpenAiApi api = OpenAiApi.builder()
                .baseUrl(config.getBaseUrl())
                .apiKey(config.getApiKey())
                .build();
        ChatModel chatModel = OpenAiChatModel.builder()
                .openAiApi(api)
                .defaultOptions(OpenAiChatOptions.builder().model(config.getModel()).build())
                .build();
        ChatClient.Builder builder = ChatClient.builder(chatModel);
        if (variant == Variant.CHAT) {
            return builder
                    .defaultAdvisors(chatMemoryAdvisor, loggerAdvisor)
                    .defaultTools(questionSaveTool, ragSearchTool, learningStatusTool)
                    .build();
        }
        return builder.build();
    }
}
