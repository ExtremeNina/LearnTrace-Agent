package com.xueji.agent.domain.dto;

import lombok.Data;

/**
 * 题目记录编辑入参：仅提供的字段会被更新
 */
@Data
public class QuestionUpdateDto {

    private String questionText;

    private String userAnswer;

    private String correctAnswer;

    private String userNote;

    private String subject;
}
