package com.xueji.agent.domain.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.experimental.Accessors;

import java.time.LocalDateTime;

/**
 * ContentDocument 语义层文档（B26 阶段 2）：
 * 对内容理解产出的统一中间模型——章节 + 知识点 + 摘要；Raw Transcript 之上的可再生产物
 */
@Data
@TableName("content_document")
@Accessors(chain = true)
public class ContentDocument {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long courseId;

    private Long userId;

    /** 内容标题（LLM 拟定） */
    private String title;

    /** 全局摘要 */
    private String summary;

    /** 生成使用的模型配置（重生成沿用；NULL = 系统默认） */
    private Long modelConfigId;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;
}
