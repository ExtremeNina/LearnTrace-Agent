package com.xueji.agent.domain.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.experimental.Accessors;

import java.time.LocalDateTime;

/**
 * 网课关键帧（画面识别结果）
 */
@Data
@TableName("course_frame")
@Accessors(chain = true)
public class CourseFrame {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long courseId;

    /** 帧在视频中的时间（秒） */
    private Integer timeSec;

    /** 帧图 OSS 访问 URL */
    private String ossKey;

    /** 该帧 OCR 识别文本 */
    private String ocrText;

    /** 识别状态：SUCCESS / FAILED */
    private String ocrStatus;

    private LocalDateTime createdAt;
}
