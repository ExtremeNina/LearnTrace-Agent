package com.xueji.agent.domain.dto;

import lombok.Data;

/**
 * 模型配置保存 / 编辑入参：编辑时 apiKey 留空表示保持原值
 */
@Data
public class AiModelSaveDto {

    private String name;

    private String baseUrl;

    private String apiKey;

    private String model;
}
