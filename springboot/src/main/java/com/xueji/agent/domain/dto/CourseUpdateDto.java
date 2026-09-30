package com.xueji.agent.domain.dto;

import lombok.Data;

/**
 * 网课编辑入参：仅提供的字段会被更新
 */
@Data
public class CourseUpdateDto {

    private String title;

    private String subject;
}
