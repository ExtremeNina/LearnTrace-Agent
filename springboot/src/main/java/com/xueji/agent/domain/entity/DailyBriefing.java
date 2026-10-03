package com.xueji.agent.domain.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.experimental.Accessors;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 每日学习简报：惰性生成（当天首次访问触发 LLM），落库缓存幂等；stats_json 保存生成时的统计快照
 */
@Data
@TableName("daily_briefing")
@Accessors(chain = true)
public class DailyBriefing {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long userId;

    private LocalDate briefDate;

    /** 生成时的学习统计快照（JSON） */
    private String statsJson;

    /** 简报正文（LLM 生成） */
    private String content;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;
}
