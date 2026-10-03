package com.xueji.agent.domain.dto;

import lombok.Data;

/**
 * 复习评分入参
 */
@Data
public class ReviewGradeDto {

    /** 评分：0 生疏 / 1 模糊 / 2 熟练 */
    private Integer grade;
}
