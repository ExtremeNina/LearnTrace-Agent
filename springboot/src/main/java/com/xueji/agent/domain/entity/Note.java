package com.xueji.agent.domain.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.experimental.Accessors;

import java.time.LocalDateTime;

/**
 * 笔记 / 知识点页（OneNote 式：一页一个知识点，Markdown 存储；parent_id 为后期知识树预留）
 */
@Data
@TableName("note")
@Accessors(chain = true)
public class Note {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long userId;

    private String title;

    /** Markdown 内容 */
    private String content;

    /** 笔记类型：普通笔记 / 知识点页 */
    private Integer noteType;

    /** 知识树父节点（MVP 不启用） */
    private Long parentId;

    /** 来源：手动创建 / 网课 AI 笔记 / Agent 生成 */
    private Integer sourceType;

    private Long courseId;

    /** 逻辑删除：0 否 / 1 是 */
    private Integer deleted;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;
}
