package com.xueji.agent.service;

import com.xueji.agent.config.ChatClientFactory;
import com.xueji.agent.domain.entity.AiModelConfig;
import com.xueji.agent.domain.entity.UserModuleModelPref;
import com.xueji.agent.domain.vo.AiModelVO;
import com.xueji.agent.exception.BusinessException;
import com.xueji.agent.mapper.AiModelConfigMapper;
import com.xueji.agent.mapper.UserModuleModelPrefMapper;
import com.xueji.agent.service.impl.AiModelServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 模型管理服务：配置 CRUD（同名拒绝 / 脱敏 / 编辑保留原 Key / 删除级联偏好）、
 * 模块偏好解析链（用户偏好 → 系统默认）、注销级联
 */
class AiModelServiceImplTest {

    private static final Long USER_ID = 5L;

    private AiModelConfigMapper aiModelConfigMapper;
    private UserModuleModelPrefMapper userModuleModelPrefMapper;
    private ChatClientFactory chatClientFactory;
    private ChatClient systemChatClient;
    private ChatClient systemGenerationClient;
    private AiModelServiceImpl service;

    @BeforeEach
    void setUp() {
        aiModelConfigMapper = mock(AiModelConfigMapper.class);
        userModuleModelPrefMapper = mock(UserModuleModelPrefMapper.class);
        chatClientFactory = mock(ChatClientFactory.class);
        systemChatClient = mock(ChatClient.class);
        systemGenerationClient = mock(ChatClient.class);
        service = new AiModelServiceImpl();
        ReflectionTestUtils.setField(service, "aiModelConfigMapper", aiModelConfigMapper);
        ReflectionTestUtils.setField(service, "userModuleModelPrefMapper", userModuleModelPrefMapper);
        ReflectionTestUtils.setField(service, "chatClientFactory", chatClientFactory);
        ReflectionTestUtils.setField(service, "systemChatClient", systemChatClient);
        ReflectionTestUtils.setField(service, "systemGenerationClient", systemGenerationClient);
    }

    private AiModelConfig config(long id) {
        return new AiModelConfig().setId(id).setUserId(USER_ID)
                .setName("智谱").setBaseUrl("https://open.bigmodel.cn/api/paas/v4")
                .setApiKey("sk-1234567890abcd").setModel("glm-4-flash")
                .setApiFormat("chat_completions").setDeleted(0);
    }

    // ---- CRUD ----

    @Test
    void addModel_shouldPersistAndMaskKey() {
        when(aiModelConfigMapper.selectCount(any())).thenReturn(0L);
        when(aiModelConfigMapper.insert(any(AiModelConfig.class))).thenReturn(1);

        AiModelVO vo = service.addModel(USER_ID, "智谱", "https://open.bigmodel.cn/api/paas/v4", "sk-1234567890abcd", "glm-4-flash");

        ArgumentCaptor<AiModelConfig> captor = ArgumentCaptor.forClass(AiModelConfig.class);
        verify(aiModelConfigMapper).insert(captor.capture());
        assertEquals("sk-1234567890abcd", captor.getValue().getApiKey());
        assertEquals("sk-****abcd", vo.getApiKeyMasked());
        assertEquals("chat_completions", vo.getApiFormat());
    }

    @Test
    void addModel_duplicateNameShouldReject() {
        when(aiModelConfigMapper.selectCount(any())).thenReturn(1L);

        assertThrows(BusinessException.class,
                () -> service.addModel(USER_ID, "智谱", "https://x.com", "sk-1", "m1"));
        verify(aiModelConfigMapper, never()).insert(any(AiModelConfig.class));
    }

    @Test
    void addModel_missingFieldsShouldReject() {
        assertThrows(BusinessException.class,
                () -> service.addModel(USER_ID, "", "https://x.com", "sk-1", "m1"));
        assertThrows(BusinessException.class,
                () -> service.addModel(USER_ID, "n", "ftp://bad", "sk-1", "m1"));
    }

    @Test
    void updateModel_blankKeyShouldKeepOriginal() {
        AiModelConfig existing = config(1L);
        when(aiModelConfigMapper.selectById(1L)).thenReturn(existing);
        when(aiModelConfigMapper.updateById(any(AiModelConfig.class))).thenReturn(1);

        service.updateModel(USER_ID, 1L, "新名字", null, "", null);

        assertEquals("新名字", existing.getName());
        assertEquals("sk-1234567890abcd", existing.getApiKey());
        verify(chatClientFactory).invalidate(1L);
    }

    @Test
    void deleteModel_shouldCascadePrefsAndInvalidate() {
        AiModelConfig existing = config(1L);
        when(aiModelConfigMapper.selectById(1L)).thenReturn(existing);
        when(aiModelConfigMapper.updateById(any(AiModelConfig.class))).thenReturn(1);

        service.deleteModel(USER_ID, 1L);

        assertEquals(1, existing.getDeleted());
        verify(userModuleModelPrefMapper).delete(any());
        verify(chatClientFactory).invalidate(1L);
    }

    // ---- 模块偏好与解析 ----

    @Test
    void resolve_shouldPreferUserConfigOverSystemDefault() {
        AiModelConfig mine = config(1L);
        when(userModuleModelPrefMapper.selectOne(any())).thenReturn(
                new UserModuleModelPref().setId(1L).setUserId(USER_ID).setModule("chat").setConfigId(1L));
        when(aiModelConfigMapper.selectById(1L)).thenReturn(mine);
        ChatClient built = mock(ChatClient.class);
        when(chatClientFactory.getChatClient(mine)).thenReturn(built);

        ChatClient client = service.resolve(USER_ID, "chat", ChatClientFactory.Variant.CHAT);

        assertSame(built, client);
    }

    @Test
    void resolve_shouldFallbackToSystemDefaultWhenNoPreference() {
        when(userModuleModelPrefMapper.selectOne(any())).thenReturn(null);
        when(userModuleModelPrefMapper.selectList(any())).thenReturn(List.of());

        ChatClient chat = service.resolve(USER_ID, "chat", ChatClientFactory.Variant.CHAT);
        ChatClient gen = service.resolve(USER_ID, "briefing", ChatClientFactory.Variant.GENERATION);

        assertSame(systemChatClient, chat);
        assertSame(systemGenerationClient, gen);
    }

    @Test
    void resolve_shouldFallbackWhenConfigDeleted() {
        when(userModuleModelPrefMapper.selectOne(any())).thenReturn(
                new UserModuleModelPref().setId(1L).setUserId(USER_ID).setModule("chat").setConfigId(1L));
        when(aiModelConfigMapper.selectById(1L)).thenReturn(
                config(1L).setDeleted(1));

        ChatClient client = service.resolve(USER_ID, "chat", ChatClientFactory.Variant.CHAT);

        assertSame(systemChatClient, client);
    }

    @Test
    void setModulePreference_shouldRejectForeignConfig() {
        when(aiModelConfigMapper.selectById(1L)).thenReturn(
                config(1L).setUserId(999L));

        assertThrows(BusinessException.class,
                () -> service.setModulePreference(USER_ID, "chat", 1L));
    }

    @Test
    void setModulePreference_shouldUpsert() {
        when(aiModelConfigMapper.selectById(1L)).thenReturn(config(1L));
        when(userModuleModelPrefMapper.selectOne(any())).thenReturn(null);
        when(userModuleModelPrefMapper.insert(any(UserModuleModelPref.class))).thenReturn(1);

        service.setModulePreference(USER_ID, "chat", 1L);

        ArgumentCaptor<UserModuleModelPref> captor = ArgumentCaptor.forClass(UserModuleModelPref.class);
        verify(userModuleModelPrefMapper).insert(captor.capture());
        assertEquals("chat", captor.getValue().getModule());
        assertEquals(1L, captor.getValue().getConfigId());
    }

    @Test
    void setModulePreference_systemDefaultShouldNotRequireConfig() {
        when(userModuleModelPrefMapper.selectOne(any())).thenReturn(null);
        when(userModuleModelPrefMapper.insert(any(UserModuleModelPref.class))).thenReturn(1);

        service.setModulePreference(USER_ID, "chat", null);

        ArgumentCaptor<UserModuleModelPref> captor = ArgumentCaptor.forClass(UserModuleModelPref.class);
        verify(userModuleModelPrefMapper).insert(captor.capture());
        assertNull(captor.getValue().getConfigId());
    }

    @Test
    void modulePreferences_unknownModulesIgnored() {
        when(userModuleModelPrefMapper.selectList(any())).thenReturn(List.of(
                new UserModuleModelPref().setId(1L).setUserId(USER_ID).setModule("chat").setConfigId(1L),
                new UserModuleModelPref().setId(2L).setUserId(USER_ID).setModule("hacker").setConfigId(9L)));

        Map<String, Long> prefs = service.getModulePreferences(USER_ID);

        assertEquals(1L, prefs.get("chat"));
        assertTrue(!prefs.containsKey("hacker"));
        // 未设置的模块条目存在、值为 null（= 系统默认）
        assertTrue(prefs.containsKey("course_note"));
        assertNull(prefs.get("course_note"));
    }

    // ---- 注销级联 ----

    @Test
    void deleteAllByUser_shouldPhysicallyDelete() {
        service.deleteAllByUser(USER_ID);

        verify(aiModelConfigMapper).delete(any());
        verify(userModuleModelPrefMapper).delete(any());
    }

    private static void assertNull(Object value) {
        org.junit.jupiter.api.Assertions.assertNull(value);
    }
}
