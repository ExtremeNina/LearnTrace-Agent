package com.xueji.agent.domain.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.experimental.Accessors;

import java.time.LocalDateTime;

/**
 * 知识联系（知识点页内跳转按钮：网课 / 题目 / 笔记）
 */
@Data
@TableName("note_link")
@Accessors(chain = true)
public class NoteLink {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long noteId;

    private Long userId;

    /** 关联类型：course / question / note */
    private String linkType;

    /** 目标资源 ID */
    private Long targetId;

    /** 展示标题 */
    private String title;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;
}
