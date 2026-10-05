package com.xueji.agent.domain.dto;

import lombok.Data;

/**
 * 播放进度上报入参：当前播放位置（秒）
 */
@Data
public class CourseProgressDto {

    private Integer positionSec;
}
