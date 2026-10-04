package com.xueji.agent.domain.vo;

import lombok.Data;
import lombok.experimental.Accessors;

import java.time.LocalDateTime;

/**
 * 用户自建模型配置视图：apiKey 一律脱敏（sk-****abcd），不回传明文
 */
@Data
@Accessors(chain = true)
public class AiModelVO {

    private Long id;

    private String name;

    private String baseUrl;

    /** 脱敏后的 API Key */
    private String apiKeyMasked;

    /** 模型名 */
    private String model;

    /** API 格式：chat_completions */
    private String apiFormat;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;
}
