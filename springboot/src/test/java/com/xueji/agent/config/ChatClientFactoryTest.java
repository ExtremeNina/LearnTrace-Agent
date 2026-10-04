package com.xueji.agent.config;

import com.xueji.agent.domain.entity.AiModelConfig;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;

import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;

/**
 * ChatClient 工厂：按配置构建双形态客户端、同配置缓存复用、invalidate 后重建。
 * 构建期不发网络请求（仅保存协议配置），可用假 Base URL 安全测试
 */
class ChatClientFactoryTest {

    private final ChatClientFactory factory = new ChatClientFactory(
            org.mockito.Mockito.mock(org.springframework.ai.chat.client.advisor.api.Advisor.class),
            org.mockito.Mockito.mock(org.springframework.ai.chat.client.advisor.api.Advisor.class),
            org.mockito.Mockito.mock(com.xueji.agent.ai.tool.QuestionSaveTool.class),
            org.mockito.Mockito.mock(com.xueji.agent.ai.tool.RagSearchTool.class),
            org.mockito.Mockito.mock(com.xueji.agent.ai.tool.LearningStatusTool.class));

    private AiModelConfig config(long id, String baseUrl) {
        return new AiModelConfig().setId(id).setUserId(5L)
                .setName("智谱").setBaseUrl(baseUrl).setApiKey("test-key")
                .setModel("glm-4-flash").setApiFormat("chat_completions").setDeleted(0);
    }

    @Test
    void shouldBuildChatAndGenerationVariants() {
        ChatClient chat = factory.getChatClient(config(1L, "https://api.example.com"));
        ChatClient gen = factory.getGenerationClient(config(1L, "https://api.example.com"));

        assertNotNull(chat);
        assertNotNull(gen);
    }

    @Test
    void sameConfigShouldReuseCachedClient() {
        ChatClient first = factory.getChatClient(config(1L, "https://api.example.com"));
        ChatClient second = factory.getChatClient(config(1L, "https://api.example.com"));

        assertSame(first, second);
    }

    @Test
    void differentConfigShouldBuildDifferentClients() {
        ChatClient a = factory.getChatClient(config(1L, "https://api.example.com"));
        ChatClient b = factory.getChatClient(config(2L, "https://api.example.com"));

        assertNotEquals(a, b);
    }

    @Test
    void invalidateShouldForceRebuild() {
        ChatClient first = factory.getChatClient(config(1L, "https://api.example.com"));

        factory.invalidate(1L);

        ChatClient rebuilt = factory.getChatClient(config(1L, "https://api.example.com"));
        assertNotEquals(first, rebuilt);
    }
}
