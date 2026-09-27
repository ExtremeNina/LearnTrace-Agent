package com.xueji.agent.ai.memory;

import com.xueji.agent.common.RedisKeys;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Redis 记忆仓库：JSON 互转与键约定
 */
@ExtendWith(MockitoExtension.class)
class RedisChatMemoryRepositoryTest {

    private static final String CONV_ID = "9";
    private static final String KEY = RedisKeys.CHAT_MEMORY_PREFIX + CONV_ID;

    @Mock
    private StringRedisTemplate stringRedisTemplate;

    @Mock
    private ValueOperations<String, String> valueOperations;

    private RedisChatMemoryRepository repository;

    @BeforeEach
    void setUp() {
        lenient().when(stringRedisTemplate.opsForValue()).thenReturn(valueOperations);
        repository = new RedisChatMemoryRepository(stringRedisTemplate);
    }

    @Test
    void saveAllShouldStoreJsonArray() {
        List<Message> messages = List.of(new UserMessage("你好"), new AssistantMessage("你好呀"));

        repository.saveAll(CONV_ID, messages);

        ArgumentCaptor<String> captor = ArgumentCaptor.forClass(String.class);
        verify(valueOperations).set(org.mockito.ArgumentMatchers.eq(KEY), captor.capture());
        String json = captor.getValue();
        assertThat(json).contains("\"role\":\"user\"").contains("你好");
        assertThat(json).contains("\"role\":\"assistant\"").contains("你好呀");
    }

    @Test
    void saveAllShouldDeleteKeyWhenMessagesEmpty() {
        repository.saveAll(CONV_ID, List.of());

        verify(stringRedisTemplate).delete(KEY);
        verify(valueOperations, never()).set(anyString(), anyString());
    }

    @Test
    void findByConversationIdShouldRestoreMessageTypes() {
        String json = "[{\"role\":\"user\",\"text\":\"你好\"},{\"role\":\"assistant\",\"text\":\"你好呀\"}]";
        when(valueOperations.get(KEY)).thenReturn(json);

        List<Message> result = repository.findByConversationId(CONV_ID);

        assertThat(result).hasSize(2);
        assertThat(result.get(0)).isInstanceOf(UserMessage.class);
        assertThat(result.get(0).getText()).isEqualTo("你好");
        assertThat(result.get(1)).isInstanceOf(AssistantMessage.class);
        assertThat(result.get(1).getText()).isEqualTo("你好呀");
    }

    @Test
    void findByConversationIdShouldReturnEmptyWhenKeyMissing() {
        when(valueOperations.get(KEY)).thenReturn(null);

        assertThat(repository.findByConversationId(CONV_ID)).isEmpty();
    }

    @Test
    void findConversationIdsShouldStripPrefix() {
        when(stringRedisTemplate.keys(RedisKeys.CHAT_MEMORY_PREFIX + "*"))
                .thenReturn(Set.of(KEY, RedisKeys.CHAT_MEMORY_PREFIX + "12"));

        List<String> ids = repository.findConversationIds();

        assertThat(ids).containsExactlyInAnyOrder("9", "12");
    }

    @Test
    void deleteByConversationIdShouldDeleteKey() {
        repository.deleteByConversationId(CONV_ID);

        verify(stringRedisTemplate).delete(KEY);
    }
}
