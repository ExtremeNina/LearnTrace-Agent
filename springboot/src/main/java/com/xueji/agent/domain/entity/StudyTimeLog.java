package com.xueji.agent.domain.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.experimental.Accessors;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 学习时长日志（一天一行，前端心跳累计；B25 首页「今日学习时长」供数）
 */
@Data
@TableName("study_time_log")
@Accessors(chain = true)
public class StudyTimeLog {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long userId;

    /** 统计日 */
    private LocalDate studyDate;

    /** 当日累计学习秒数 */
    private Integer durationSec;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;
}
