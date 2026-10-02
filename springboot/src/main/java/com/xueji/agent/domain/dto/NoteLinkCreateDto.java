package com.xueji.agent.domain.dto;

import lombok.Data;

/**
 * 知识联系新增入参：展示标题由后端按 linkType + targetId 解析，前端不传
 */
@Data
public class NoteLinkCreateDto {

    /** 联系类型：course / question / note */
    private String linkType;

    /** 目标 ID（按 linkType 指向网课 / 题目 / 笔记） */
    private Long targetId;

    /** 网课时间戳（秒，可选） */
    private Integer tsSec;

    /** 关联说明（可选） */
    private String remark;
}
