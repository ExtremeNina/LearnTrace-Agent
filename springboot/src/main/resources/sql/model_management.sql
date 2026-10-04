-- 模型管理（用户自建 OpenAI 兼容模型配置 + 按模块的模型偏好）
-- ai_model_config：用户自建的模型配置（密钥明文落库，接口脱敏返回；注销时物理删除）
CREATE TABLE IF NOT EXISTS ai_model_config (
    id bigint unsigned NOT NULL AUTO_INCREMENT COMMENT '主键',
    user_id bigint unsigned NOT NULL COMMENT '所属用户',
    name varchar(64) NOT NULL COMMENT '显示名（如：智谱 GLM）',
    base_url varchar(255) NOT NULL COMMENT '供应商 OpenAI 兼容端点',
    api_key varchar(255) NOT NULL COMMENT 'API Key（明文存储，接口脱敏返回）',
    model varchar(64) NOT NULL COMMENT '模型名（传给供应商的 model 参数，如 glm-4-flash）',
    api_format varchar(32) NOT NULL DEFAULT 'chat_completions' COMMENT 'API 格式（当前仅 chat_completions）',
    deleted tinyint NOT NULL DEFAULT 0 COMMENT '0 正常 / 1 已删除',
    created_at datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    updated_at datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (id),
    UNIQUE KEY uk_user_name (user_id, name)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='用户自建 AI 模型配置（OpenAI 兼容）';

-- user_model_pref：用户按模块选择的模型偏好（config_id NULL = 系统默认模型）
CREATE TABLE IF NOT EXISTS user_model_pref (
    id bigint unsigned NOT NULL AUTO_INCREMENT COMMENT '主键',
    user_id bigint unsigned NOT NULL COMMENT '所属用户',
    module varchar(32) NOT NULL COMMENT '模块：chat / course_note / briefing',
    config_id bigint unsigned NULL COMMENT '模型配置 ID，NULL = 系统默认',
    updated_at datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (id),
    UNIQUE KEY uk_user_module (user_id, module)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='用户按模块的模型偏好';
