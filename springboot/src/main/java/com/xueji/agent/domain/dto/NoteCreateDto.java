package com.xueji.agent.domain.dto;

import lombok.Data;

/**
 * 笔记新建入参：分组用 name，笔记用 title（前端两个接口的请求体同形）
 */
@Data
public class NoteCreateDto {

    /** 父分组 ID，null 表示根目录 */
    private Long parentId;

    /** 分组名（新建分组时使用） */
    private String name;

    /** 笔记标题（新建笔记时使用） */
    private String title;
}
