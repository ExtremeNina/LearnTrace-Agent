package com.xueji.agent.domain.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.experimental.Accessors;

import java.time.LocalDateTime;

/**
 * 复习评分流水：每次评分一行（历史正确率统计 + 将来 FSRS 升级的数据基础）
 */
@Data
@TableName("review_log")
@Accessors(chain = true)
public class ReviewLog {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long userId;

    private Long cardId;

    /** 评分：0 生疏 / 1 模糊 / 2 熟练 */
    private Integer grade;

    /** 评分后间隔（天） */
    private Integer intervalAfter;

    private LocalDateTime reviewedAt;
}
