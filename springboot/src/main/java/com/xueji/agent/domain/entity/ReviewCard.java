package com.xueji.agent.domain.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.xueji.agent.common.UserOwned;
import lombok.Data;
import lombok.experimental.Accessors;

import java.time.LocalDateTime;
import java.math.BigDecimal;

/**
 * 复习卡（间隔重复调度状态）：统一四种来源（question / similar / note），
 * 只存调度状态不复制内容，正面 / 背面按 cardType 实时组装
 */
@Data
@TableName("review_card")
@Accessors(chain = true)
public class ReviewCard implements UserOwned {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long userId;

    /** 卡片类型：question / similar / note */
    private String cardType;

    /** 来源实体主键 */
    private Long refId;

    /** 下次到期时间 */
    private LocalDateTime dueAt;

    /** 当前间隔（天） */
    private Integer intervalDays;

    /** 容易度（SM-2 简化，1.30 ~ 3.00） */
    private BigDecimal ease;

    private Integer reps;

    private Integer lapses;

    /** 0 队列中 / 1 已移出 */
    private Integer deleted;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;
}
