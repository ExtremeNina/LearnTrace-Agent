package com.xueji.agent.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.xueji.agent.common.OwnershipCheck;
import com.xueji.agent.config.ChatClientFactory;
import com.xueji.agent.domain.entity.AiModelConfig;
import com.xueji.agent.domain.entity.ReviewCard;
import com.xueji.agent.domain.entity.UserModuleModelPref;
import com.xueji.agent.domain.vo.AiModelVO;
import com.xueji.agent.exception.BusinessException;
import com.xueji.agent.mapper.AiModelConfigMapper;
import com.xueji.agent.mapper.UserModuleModelPrefMapper;
import com.xueji.agent.service.AiModelService;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 模型管理实现：用户自建 OpenAI 兼容配置的 CRUD（密钥脱敏）、连接测试、模块偏好与 ChatClient 解析。
 * 解析链：用户模块偏好（config_id）→ 系统默认模型；配置删除时级联清理偏好（自动回退系统默认）
 */
@Slf4j
@Service
public class AiModelServiceImpl implements AiModelService {

    public static final String API_FORMAT = "chat_completions";

    private static final Set<String> MODULES = Set.of(
            AiModelService.MODULE_CHAT, AiModelService.MODULE_COURSE_NOTE, AiModelService.MODULE_BRIEFING);

    @Resource
    private AiModelConfigMapper aiModelConfigMapper;

    @Resource
    private UserModuleModelPrefMapper userModuleModelPrefMapper;

    @Resource
    private ChatClientFactory chatClientFactory;

    @Resource(name = "chatClient")
    private ChatClient systemChatClient;

    @Resource(name = "generationChatClient")
    private ChatClient systemGenerationClient;

    @Override
    public List<AiModelVO> listModels(Long userId) {
        return aiModelConfigMapper.selectList(new QueryWrapper<AiModelConfig>()
                        .eq("user_id", userId)
                        .eq("deleted", 0)
                        .orderByDesc("created_at"))
                .stream().map(this::toVO).toList();
    }

    @Override
    public AiModelVO addModel(Long userId, String name, String baseUrl, String apiKey, String model) {
        validateFields(name, baseUrl, apiKey, model);
        Long exists = aiModelConfigMapper.selectCount(new QueryWrapper<AiModelConfig>()
                .eq("user_id", userId)
                .eq("name", name.trim())
                .eq("deleted", 0));
        if (exists > 0) {
            throw new BusinessException("同名配置已存在");
        }
        AiModelConfig config = new AiModelConfig()
                .setUserId(userId)
                .setName(name.trim())
                .setBaseUrl(baseUrl.trim())
                .setApiKey(apiKey.trim())
                .setModel(model.trim())
                .setApiFormat(API_FORMAT)
                .setDeleted(0)
                .setCreatedAt(LocalDateTime.now())
                .setUpdatedAt(LocalDateTime.now());
        aiModelConfigMapper.insert(config);
        log.info("模型配置已添加, userId={}, configId={}, name={}", userId, config.getId(), config.getName());
        return toVO(config);
    }

    @Override
    public AiModelVO updateModel(Long userId, Long id, String name, String baseUrl, String apiKey, String model) {
        AiModelConfig config = ownedConfig(userId, id);
        if (name != null && !name.isBlank()) {
            config.setName(name.trim());
        }
        if (baseUrl != null && !baseUrl.isBlank()) {
            config.setBaseUrl(baseUrl.trim());
        }
        // apiKey 留空 = 保持原值（前端不回显明文）
        if (apiKey != null && !apiKey.isBlank()) {
            config.setApiKey(apiKey.trim());
        }
        if (model != null && !model.isBlank()) {
            config.setModel(model.trim());
        }
        config.setUpdatedAt(LocalDateTime.now());
        aiModelConfigMapper.updateById(config);
        chatClientFactory.invalidate(config.getId());
        return toVO(config);
    }

    @Override
    public void deleteModel(Long userId, Long id) {
        AiModelConfig config = ownedConfig(userId, id);
        config.setDeleted(1).setUpdatedAt(LocalDateTime.now());
        aiModelConfigMapper.updateById(config);
        // 引用该配置的模块偏好级联清除（回退系统默认）
        userModuleModelPrefMapper.delete(new QueryWrapper<UserModuleModelPref>()
                .eq("user_id", userId)
                .eq("config_id", id));
        chatClientFactory.invalidate(config.getId());
    }

    @Override
    public void testConnection(Long userId, Long id) {
        AiModelConfig config = ownedConfig(userId, id);
        testConnectionDraft(userId, config.getBaseUrl(), config.getApiKey(), config.getModel());
    }

    @Override
    public void testConnectionDraft(Long userId, String baseUrl, String apiKey, String model) {
        if (baseUrl == null || baseUrl.isBlank() || apiKey == null || apiKey.isBlank() || model == null || model.isBlank()) {
            throw new BusinessException("Base URL / API Key / 模型名均不能为空");
        }
        AiModelConfig draft = new AiModelConfig()
                .setUserId(userId)
                .setBaseUrl(baseUrl.trim())
                .setApiKey(apiKey.trim())
                .setModel(model.trim());
        try {
            // 极小请求验证连通性（限制 maxTokens 控制成本）
            ChatClient client = chatClientFactory.buildTransient(draft);
            client.prompt()
                    .user("ping")
                    .options(OpenAiChatOptions.builder().maxTokens(16).build())
                    .call()
                    .content();
            log.info("模型连接测试成功, userId={}, baseUrl={}, model={}", userId, baseUrl, model);
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            log.warn("模型连接测试失败, userId={}, baseUrl={}, model={}", userId, model, e.getMessage());
            throw new BusinessException("连接失败：" + rootMessage(e));
        }
    }

    @Override
    public Map<String, Long> getModulePreferences(Long userId) {
        Map<String, Long> prefs = new HashMap<>();
        for (String module : MODULES) {
            prefs.put(module, null);
        }
        for (UserModuleModelPref pref : userModuleModelPrefMapper.selectList(new QueryWrapper<UserModuleModelPref>()
                .eq("user_id", userId))) {
            if (MODULES.contains(pref.getModule())) {
                prefs.put(pref.getModule(), pref.getConfigId());
            }
        }
        return prefs;
    }

    @Override
    public void setModulePreference(Long userId, String module, Long configId) {
        if (!MODULES.contains(module)) {
            throw new BusinessException("不支持的模块");
        }
        if (configId != null) {
            // 绑定的配置必须存在且属于本人
            OwnershipCheck.requireOwned(aiModelConfigMapper.selectById(configId), userId, "模型配置不存在");
        }
        UserModuleModelPref pref = userModuleModelPrefMapper.selectOne(new QueryWrapper<UserModuleModelPref>()
                .eq("user_id", userId)
                .eq("module", module));
        if (pref == null) {
            pref = new UserModuleModelPref()
                    .setUserId(userId)
                    .setModule(module)
                    .setConfigId(configId)
                    .setUpdatedAt(LocalDateTime.now());
            userModuleModelPrefMapper.insert(pref);
        } else {
            pref.setConfigId(configId).setUpdatedAt(LocalDateTime.now());
            userModuleModelPrefMapper.updateById(pref);
        }
    }

    @Override
    public ChatClient resolve(Long userId, String module, ChatClientFactory.Variant variant) {
        UserModuleModelPref pref = userModuleModelPrefMapper.selectOne(new QueryWrapper<UserModuleModelPref>()
                .eq("user_id", userId)
                .eq("module", module));
        if (pref == null || pref.getConfigId() == null) {
            return systemClient(variant);
        }
        AiModelConfig config = aiModelConfigMapper.selectById(pref.getConfigId());
        if (config == null || Integer.valueOf(1).equals(config.getDeleted())) {
            // 引用的配置已被删除：回退系统默认（偏好行随删除级联清理，这里兜底）
            return systemClient(variant);
        }
        return variant == ChatClientFactory.Variant.CHAT
                ? chatClientFactory.getChatClient(config)
                : chatClientFactory.getGenerationClient(config);
    }

    @Override
    public void validateUserConfig(Long userId, Long configId) {
        if (configId == null) {
            return;
        }
        OwnershipCheck.requireOwned(aiModelConfigMapper.selectById(configId), userId, "所选模型配置不存在");
    }

    @Override
    public ChatClient resolveGenerationForCourse(Long userId, Long configId) {
        if (configId == null) {
            return systemGenerationClient;
        }
        AiModelConfig config = aiModelConfigMapper.selectById(configId);
        // 配置缺失 / 已删 / 非本人：静默回退系统默认（流水线不因模型配置失效而中断）
        if (config == null || Integer.valueOf(1).equals(config.getDeleted()) || config.getUserId() == null
                || !config.getUserId().equals(userId)) {
            log.warn("网课笔记模型配置不可用，回退系统默认, userId={}, configId={}", userId, configId);
            return systemGenerationClient;
        }
        return chatClientFactory.getGenerationClient(config);
    }

    /** 注销账号：物理删除该用户的全部模型配置（含密钥）与模块偏好 */
    public void deleteAllByUser(Long userId) {
        aiModelConfigMapper.delete(new QueryWrapper<AiModelConfig>().eq("user_id", userId));
        userModuleModelPrefMapper.delete(new QueryWrapper<UserModuleModelPref>().eq("user_id", userId));
    }

    private ChatClient systemClient(ChatClientFactory.Variant variant) {
        return variant == ChatClientFactory.Variant.CHAT ? systemChatClient : systemGenerationClient;
    }

    private AiModelConfig ownedConfig(Long userId, Long id) {
        return OwnershipCheck.requireOwned(aiModelConfigMapper.selectById(id), userId, "模型配置不存在");
    }

    private void validateFields(String name, String baseUrl, String apiKey, String model) {
        if (name == null || name.isBlank() || baseUrl == null || baseUrl.isBlank()
                || apiKey == null || apiKey.isBlank() || model == null || model.isBlank()) {
            throw new BusinessException("显示名 / Base URL / API Key / 模型名均不能为空");
        }
        if (!baseUrl.startsWith("http://") && !baseUrl.startsWith("https://")) {
            throw new BusinessException("Base URL 必须以 http(s):// 开头");
        }
    }

    private String rootMessage(Throwable e) {
        Throwable cur = e;
        while (cur.getCause() != null && cur.getCause() != cur) {
            cur = cur.getCause();
        }
        return cur.getMessage() == null ? cur.getClass().getSimpleName() : cur.getMessage();
    }

    private AiModelVO toVO(AiModelConfig config) {
        String key = config.getApiKey() == null ? "" : config.getApiKey();
        String masked = key.length() <= 8
                ? "****"
                : key.substring(0, 3) + "****" + key.substring(key.length() - 4);
        return new AiModelVO()
                .setId(config.getId())
                .setName(config.getName())
                .setBaseUrl(config.getBaseUrl())
                .setApiKeyMasked(masked)
                .setModel(config.getModel())
                .setApiFormat(config.getApiFormat())
                .setCreatedAt(config.getCreatedAt())
                .setUpdatedAt(config.getUpdatedAt());
    }
}
