package com.xueji.agent.domain.dto;

import lombok.Data;

import java.util.List;

/**
 * 批量加入复习入参（练习模式交卷后一键入队）
 */
@Data
public class AddReviewCardsDto {

    /** 要加入的卡片列表 */
    private List<AddReviewCardDto> items;
}
