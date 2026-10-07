package com.xueji.agent.domain.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.experimental.Accessors;

/**
 * ContentDocument 知识点（B26 阶段 2）：名称 + 说明 + 关键时间点 + 重要 / 易错标记
 */
@Data
@TableName("content_knowledge_point")
@Accessors(chain = true)
public class ContentKnowledgePoint {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long documentId;

    private String name;

    /** 知识点说明 */
    private String detail;

    /** 关键时间点（秒，可 NULL） */
    private Integer timeSec;

    /** 归属章节序号（弱关联，便于排序） */
    private Integer sectionSort;

    /** 1=重点 / 0=普通 */
    private Integer important;

    /** 1=易错点 */
    private Integer errorProne;

    private Integer sort;
}
