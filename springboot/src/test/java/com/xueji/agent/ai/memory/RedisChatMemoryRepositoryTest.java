package com.xueji.agent.ai.memory;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.xueji.agent.common.RedisKeys;
import com.xueji.agent.mapper.MessageMapper;
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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Redis 记忆仓库：JSON 互转与键约定、缓存缺失时自 MySQL 事实源重建（PRD §6 读路径兜底）
 */
@ExtendWith(MockitoExtension.class)
class RedisChatMemoryRepositoryTest {

    private static final String CONV_ID = "9";
    private static final String KEY = RedisKeys.CHAT_MEMORY_PREFIX + CONV_ID;

    @Mock
    private StringRedisTemplate stringRedisTemplate;

    @Mock
    private ValueOperations<String, String> valueOperations;

    @Mock
    private MessageMapper messageMapper;

    private RedisChatMemoryRepository repository;

    @BeforeEach
    void setUp() {
        lenient().when(stringRedisTemplate.opsForValue()).thenReturn(valueOperations);
        repository = new RedisChatMemoryRepository(stringRedisTemplate, messageMapper, 100);
    }

    private com.xueji.agent.domain.entity.Message dbRow(long id, String role, String content) {
        return new com.xueji.agent.domain.entity.Message()
                .setId(id).setConversationId(9L).setRole(role).setContent(content);
    }

    // ---- JSON 互转与键约定 ----

    @Test
    void saveAllShouldStoreJsonArray() {
        List<Message> messages = List.of(new UserMessage("你好"), new AssistantMessage("你好呀"));

        repository.saveAll(CONV_ID, messages);

        ArgumentCaptor<String> captor = ArgumentCaptor.forClass(String.class);
        verify(valueOperations).set(eq(KEY), captor.capture());
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
        verify(messageMapper, never()).selectList(any());
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

    // ---- 缓存缺失时自 MySQL 重建（PRD §6）----

    @Test
    void missingCacheShouldRebuildFromDbAndBackfill() {
        when(valueOperations.get(KEY)).thenReturn(null);
        // SQL 按 id 倒序取窗口，mock 同样给倒序结果
        when(messageMapper.selectList(any())).thenReturn(List.of(
                dbRow(4, "assistant", "第二步……"),
                dbRow(3, "user", "第二题怎么解"),
                dbRow(2, "assistant", "第一步……"),
                dbRow(1, "user", "第一题怎么解")));

        List<Message> result = repository.findByConversationId(CONV_ID);

        assertThat(result).hasSize(4);
        assertThat(result.get(0).getText()).isEqualTo("第一题怎么解");
        assertThat(result.get(3).getText()).isEqualTo("第二步……");
        // 重建结果回填 Redis
        ArgumentCaptor<String> captor = ArgumentCaptor.forClass(String.class);
        verify(valueOperations).set(eq(KEY), captor.capture());
        assertThat(captor.getValue()).contains("第一题怎么解").contains("第二步……");
    }

    @Test
    void rebuildShouldReattachRecognizedQuestionText() {
        when(valueOperations.get(KEY)).thenReturn(null);
        com.xueji.agent.domain.entity.Message photoTurn = dbRow(1, "user", "请看这张图片");
        photoTurn.setPayload("{\"imageUrl\":\"oss://a.png\",\"questionText\":\"已知函数 f(x)=ln(x+1)-x\"}");
        when(messageMapper.selectList(any())).thenReturn(List.of(
                dbRow(2, "assistant", "极大值为 0"), photoTurn));

        List<Message> result = repository.findByConversationId(CONV_ID);

        // 重建内容与原先写入记忆的带附录提示形态一致
        assertThat(result.get(0).getText())
                .isEqualTo("请看这张图片\n\n[题目图片识别文本（已整理）]\n已知函数 f(x)=ln(x+1)-x");
    }

    @Test
    void rebuildShouldDropTrailingUnpairedUserMessage() {
        when(valueOperations.get(KEY)).thenReturn(null);
        // 末尾用户消息是回合进行中刚落库的事实源（尚无回复），不能进记忆，否则 advisor 再写入会重复
        when(messageMapper.selectList(any())).thenReturn(List.of(
                dbRow(3, "user", "本轮刚发出的消息"),
                dbRow(2, "assistant", "历史回答"),
                dbRow(1, "user", "历史问题")));

        List<Message> result = repository.findByConversationId(CONV_ID);

        assertThat(result).hasSize(2);
        assertThat(result.get(0).getText()).isEqualTo("历史问题");
        assertThat(result.get(1).getText()).isEqualTo("历史回答");
    }

    @Test
    void nonAgentConversationShouldSkipRebuild() {
        when(valueOperations.get(RedisKeys.CHAT_MEMORY_PREFIX + "course-note-9")).thenReturn(null);

        assertThat(repository.findByConversationId("course-note-9")).isEmpty();
        verify(messageMapper, never()).selectList(any());
    }

    @Test
    void rebuildFailureShouldDegradeToEmpty() {
        when(valueOperations.get(KEY)).thenReturn(null);
        when(messageMapper.selectList(any())).thenThrow(new IllegalStateException("db down"));

        assertThat(repository.findByConversationId(CONV_ID)).isEmpty();
        verify(valueOperations, never()).set(anyString(), anyString());
    }

    @Test
    void rebuildQueryShouldFilterByConversationAndRoles() {
        when(valueOperations.get(KEY)).thenReturn(null);
        when(messageMapper.selectList(any())).thenReturn(List.of());

        repository.findByConversationId(CONV_ID);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<QueryWrapper<com.xueji.agent.domain.entity.Message>> captor =
                ArgumentCaptor.forClass((Class) QueryWrapper.class);
        verify(messageMapper).selectList(captor.capture());
        String sql = captor.getValue().getSqlSegment();
        assertThat(sql).contains("conversation_id");
        assertThat(sql).contains("role IN");
        assertThat(sql).contains("ORDER BY id DESC");
        assertThat(sql).contains("LIMIT 100");
    }
}
