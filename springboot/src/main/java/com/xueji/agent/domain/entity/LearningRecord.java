package com.xueji.agent.domain.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.experimental.Accessors;

import java.time.LocalDateTime;

/**
 * 学习轨迹事件（MVP 纯数据层：记录事件、供 Agent 查询）
 */
@Data
@TableName("learning_record")
@Accessors(chain = true)
public class LearningRecord {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long userId;

    /** 事件类型：view_course / do_question / review_note 等 */
    private String eventType;

    /** 资源类型：course / question / note */
    private String resourceType;

    private Long resourceId;

    private Long noteId;

    /** 事件明细（JSON） */
    private String detail;

    private LocalDateTime createdAt;
}
