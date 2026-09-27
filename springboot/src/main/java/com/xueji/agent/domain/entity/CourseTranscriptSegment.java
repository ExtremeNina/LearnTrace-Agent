package com.xueji.agent.domain.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.experimental.Accessors;

import java.time.LocalDateTime;

/**
 * 网课转写片段（转写产物，自动入库无需确认）
 */
@Data
@TableName("course_transcript_segment")
@Accessors(chain = true)
public class CourseTranscriptSegment {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long courseId;

    /** 片段起始秒 */
    private Integer startSec;

    /** 片段结束秒 */
    private Integer endSec;

    private String text;

    private Integer sort;

    private LocalDateTime createdAt;
}
