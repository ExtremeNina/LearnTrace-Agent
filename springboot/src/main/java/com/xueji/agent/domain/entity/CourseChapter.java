package com.xueji.agent.domain.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.experimental.Accessors;

import java.time.LocalDateTime;

/**
 * 网课章节（AI 生成，需用户确认后应用）
 */
@Data
@TableName("course_chapter")
@Accessors(chain = true)
public class CourseChapter {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long courseId;

    private String title;

    /** 章节起始秒 */
    private Integer startSec;

    /** 章节结束秒 */
    private Integer endSec;

    private String summary;

    private Integer sort;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;
}
