package com.xueji.agent.domain.dto;

import lombok.Data;

/**
 * 加入复习入参
 */
@Data
public class AddReviewCardDto {

    /** 卡片类型：question / similar / note */
    private String cardType;

    /** 来源实体主键 */
    private Long refId;
}
