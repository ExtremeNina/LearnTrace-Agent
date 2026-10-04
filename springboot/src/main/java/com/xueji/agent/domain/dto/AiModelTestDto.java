package com.xueji.agent.domain.dto;

import lombok.Data;

/**
 * 模型连接测试入参
 */
@Data
public class AiModelTestDto {

    private String baseUrl;

    private String apiKey;

    private String model;
}
