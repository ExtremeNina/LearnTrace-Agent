package com.xueji.agent.domain.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.xueji.agent.common.UserOwned;
import lombok.Data;
import lombok.experimental.Accessors;

import java.time.LocalDateTime;

/**
 * 课程课后习题（B26 习题产物化）：出题 Agent 自动产出、判题 Agent 把关后落库的课程级产物；
 * 用户显式操作才沉淀入题目管理 / 复习计划
 */
@Data
@TableName("course_quiz_question")
@Accessors(chain = true)
public class CourseQuizQuestion implements UserOwned {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long courseId;

    private Long userId;

    /** 题面 */
    private String questionText;

    /** 参考答案 */
    private String answer;

    /** 解析（含依据说明） */
    private String analysis;

    /** 依据时间点（秒） */
    private Integer sourceSec;

    private Integer sort;

    private LocalDateTime createdAt;

    /** 习题无软删（重生成即物理替换），恒返回 0 满足归属校验 */
    @TableField(exist = false)
    private Integer deleted = 0;
}
