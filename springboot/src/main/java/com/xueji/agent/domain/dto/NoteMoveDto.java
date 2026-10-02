package com.xueji.agent.domain.dto;

import lombok.Data;

/**
 * 笔记移动入参
 */
@Data
public class NoteMoveDto {

    /** 目标父分组 ID，null 表示根目录 */
    private Long parentId;
}
