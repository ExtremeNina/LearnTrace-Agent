package com.xueji.agent.domain.dto;

import lombok.Data;

/**
 * 偏好设置入参：主题与任务通知开关，仅提供的字段会被更新
 */
@Data
public class UpdatePreferenceDto {

    /** 界面主题：LIGHT / DARK */
    private String theme;

    /** 任务完成/失败通知开关；null 表示不变更 */
    private Boolean notifyTaskEnabled;
}
