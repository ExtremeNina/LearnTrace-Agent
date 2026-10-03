package com.xueji.agent.domain.vo;

import lombok.Data;
import lombok.experimental.Accessors;

import java.time.LocalDateTime;
import java.util.Map;

/**
 * 每日简报视图：统计快照 + LLM 生成的正文
 */
@Data
@Accessors(chain = true)
public class BriefingVO {

    /** 简报日期（yyyy-MM-dd） */
    private String briefDate;

    /** 简报正文（Markdown） */
    private String content;

    /** 生成时的学习统计快照 */
    private Map<String, Object> stats;

    private LocalDateTime generatedAt;
}
