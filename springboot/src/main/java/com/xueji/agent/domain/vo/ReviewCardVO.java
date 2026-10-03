package com.xueji.agent.domain.vo;

import lombok.Data;
import lombok.experimental.Accessors;

import java.time.LocalDateTime;

/**
 * 复习卡视图：正面 / 背面内容按 cardType 实时组装（question / similar → 题干与解答；note → 标题与正文）
 */
@Data
@Accessors(chain = true)
public class ReviewCardVO {

    private Long id;

    /** question / similar / note */
    private String cardType;

    /** 来源实体主键 */
    private Long refId;

    /** 正面：题干 / 笔记标题 */
    private String frontText;

    /** 背面：解答 / 笔记正文（Markdown，前端渲染） */
    private String backText;

    /** 错因 / 解析（可选） */
    private String analysis;

    /** 题目图片（仅拍照题，正面辅助回忆） */
    private String imageUrl;

    private LocalDateTime dueAt;

    private Integer intervalDays;

    private Integer reps;

    private Integer lapses;
}
