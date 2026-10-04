package com.xueji.agent.domain.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.xueji.agent.common.UserOwned;
import lombok.Data;
import lombok.experimental.Accessors;

import java.time.LocalDateTime;

/**
 * 用户自建 AI 模型配置（OpenAI 兼容协议）：密钥明文落库（本地单人部署），
 * 接口一律脱敏返回；注销账号时物理删除
 */
@Data
@TableName("ai_model_config")
@Accessors(chain = true)
public class AiModelConfig implements UserOwned {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long userId;

    /** 显示名（如：智谱 GLM） */
    private String name;

    /** 供应商 OpenAI 兼容端点 */
    private String baseUrl;

    /** API Key */
    private String apiKey;

    /** 模型名（传给供应商的 model 参数） */
    private String model;

    /** API 格式：chat_completions（当前仅此一种） */
    private String apiFormat;

    /** 0 正常 / 1 已删除 */
    private Integer deleted;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;
}
