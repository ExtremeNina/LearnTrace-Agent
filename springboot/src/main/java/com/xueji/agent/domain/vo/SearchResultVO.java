package com.xueji.agent.domain.vo;

import lombok.Data;
import lombok.experimental.Accessors;

/**
 * 全局搜索单条结果（B15）：语义召回的学习片段，前端按 type 分组跳转
 */
@Data
@Accessors(chain = true)
public class SearchResultVO {

    /** question / note / transcript */
    private String type;

    /** question=题目 ID / note=笔记 ID / transcript=网课 ID */
    private Long refId;

    /** 网课片段的归属课程（仅 transcript） */
    private Long courseId;

    /** 网课片段起始秒（仅 transcript，跳转带时间戳） */
    private Integer tsSec;

    /** 学科（仅题目） */
    private String subject;

    /** 是否错题（仅题目） */
    private Boolean wrong;

    /** 命中文本 */
    private String snippet;
}
