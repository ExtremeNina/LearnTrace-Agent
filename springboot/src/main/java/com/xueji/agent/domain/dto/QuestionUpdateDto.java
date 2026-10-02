package com.xueji.agent.domain.dto;

import lombok.Data;

/**
 * 题目记录编辑入参：仅提供的字段会被更新（相似题仅 questionText / correctAnswer / analysis / subject 生效）
 */
@Data
public class QuestionUpdateDto {

    private String questionText;

    private String userAnswer;

    private String correctAnswer;

    /** 错因 / 解析 */
    private String analysis;

    private String userNote;

    private String subject;
}
