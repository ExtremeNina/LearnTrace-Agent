package com.xueji.agent.domain.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.experimental.Accessors;

import java.time.LocalDateTime;

/**
 * 学习者画像（B26 阶段 3）：评审与笔记生成的难度适配输入；未填写时评审按通用学习者处理
 */
@Data
@TableName("user_profile")
@Accessors(chain = true)
public class UserProfile {

    @TableId(type = IdType.INPUT)
    private Long userId;

    /** 学段（如初中 / 高中 / 大学） */
    private String gradeLevel;

    /** 自评水平（入门 / 进阶） */
    private String level;

    /** 学习目标 */
    private String goal;

    /** 补充说明（偏好、薄弱点等） */
    private String note;

    private LocalDateTime updatedAt;
}
