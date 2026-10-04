package com.xueji.agent.domain.vo;

import lombok.Data;
import lombok.experimental.Accessors;

import java.time.LocalDateTime;

/**
 * 练习抽题项（对应后端 /quiz/pick；cardType + refId 可直接用于加入复习队列）
 */
@Data
@Accessors(chain = true)
public class QuizPickVO {

    /** 来源类型：question（拍照题目）/ similar（AI 相似题） */
    private String cardType;

    /** 来源实体主键 */
    private Long refId;

    /** 题干 */
    private String questionText;

    /** 题目图片 URL（仅拍照题目可能有） */
    private String imageUrl;

    /** 参考答案 */
    private String correctAnswer;

    /** 解析 / 错因 */
    private String analysis;

    /** 学科 */
    private String subject;

    /** 入库时间 */
    private LocalDateTime createdAt;
}
