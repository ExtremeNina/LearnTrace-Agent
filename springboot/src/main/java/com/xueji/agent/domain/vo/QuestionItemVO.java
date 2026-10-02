package com.xueji.agent.domain.vo;

import lombok.Data;
import lombok.experimental.Accessors;

import java.time.LocalDateTime;

/**
 * 题目列表 / 详情统一视图：拍照题目（question_record）与 AI 生成的相似题（similar_question）
 * 在拍照记录页合并展示，以 source 区分来源
 */
@Data
@Accessors(chain = true)
public class QuestionItemVO {

    /** 拍照题目 */
    public static final String SOURCE_PHOTO = "photo";

    /** AI 生成的相似题 */
    public static final String SOURCE_SIMILAR_AI = "similar_ai";

    private Long id;

    /** photo = 拍照题目 / similar_ai = AI 生成的相似题 */
    private String source;

    private String questionText;

    private String subject;

    /** 仅拍照题目有 */
    private String imageOssKey;

    /** 相似题的 answer 映射到该字段 */
    private String correctAnswer;

    private String analysis;

    /** 仅拍照题目有（0 正确 / 1 错误 / NULL 未判定；相似题的作答结果二期启用） */
    private Integer isWrong;

    /** 仅拍照题目有 */
    private String userAnswer;

    /** 仅拍照题目有 */
    private String userNote;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;
}
