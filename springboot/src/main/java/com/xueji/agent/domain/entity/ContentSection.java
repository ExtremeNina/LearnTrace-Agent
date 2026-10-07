package com.xueji.agent.domain.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.experimental.Accessors;

/**
 * ContentDocument 章节（B26 阶段 2）：标题 + 时间范围 + 摘要
 */
@Data
@TableName("content_section")
@Accessors(chain = true)
public class ContentSection {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long documentId;

    private String title;

    private String summary;

    /** 起始秒（来自转写时间戳） */
    private Integer startSec;

    /** 结束秒 */
    private Integer endSec;

    private Integer sort;
}
