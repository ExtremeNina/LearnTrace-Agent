package com.xueji.agent.service;

import com.xueji.agent.config.ChatClientFactory;
import com.xueji.agent.domain.vo.AiModelVO;
import org.springframework.ai.chat.client.ChatClient;

import java.util.List;
import java.util.Map;

/**
 * 模型管理：用户自建 OpenAI 兼容模型配置的增删改查（密钥脱敏）、连接测试、
 * 按模块的模型偏好（chat / course_note / briefing），以及各模块 ChatClient 的解析入口。
 * 解析链：用户模块偏好（config_id）→ 系统默认模型（application-local.properties 配置的 DeepSeek）
 */
public interface AiModelService {

    /** 模块：对话 */
    String MODULE_CHAT = "chat";
    /** 模块：网课 AI 笔记生成 */
    String MODULE_COURSE_NOTE = "course_note";
    /** 模块：每日简报 */
    String MODULE_BRIEFING = "briefing";

    /** 我的模型配置列表（密钥脱敏） */
    List<AiModelVO> listModels(Long userId);

    /** 添加配置（显示名同名拒绝，可附带是否测试连接） */
    AiModelVO addModel(Long userId, String name, String baseUrl, String apiKey, String model);

    /** 编辑配置：apiKey 为空表示保持原值；变更后缓存失效 */
    AiModelVO updateModel(Long userId, Long id, String name, String baseUrl, String apiKey, String model);

    /** 删除配置：级联清理该配置上的模块偏好（自动回退系统默认），缓存失效 */
    void deleteModel(Long userId, Long id);

    /** 测试连接：向供应商发一个极小请求验证 Base URL + Key + 模型名 */
    void testConnection(Long userId, Long id);

    /** 测试连接（未落库的草稿配置，弹窗"测试连接"按钮用） */
    void testConnectionDraft(Long userId, String baseUrl, String apiKey, String model);

    /** 模块偏好：module → 模型配置 ID（null = 系统默认） */
    Map<String, Long> getModulePreferences(Long userId);

    /** 设置模块偏好：configId 为空表示系统默认；config 必须存在且属于本人 */
    void setModulePreference(Long userId, String module, Long configId);

    /** 解析某模块当前应使用的 ChatClient（对话用 CHAT 形态，生成类用 GENERATION 形态） */
    ChatClient resolve(Long userId, String module, ChatClientFactory.Variant variant);

    /** 注销账号级联：物理删除该用户的全部模型配置（含密钥）与模块偏好 */
    void deleteAllByUser(Long userId);
}
