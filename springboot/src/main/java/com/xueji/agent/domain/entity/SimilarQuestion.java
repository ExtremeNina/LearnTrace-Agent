package com.xueji.agent.domain.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.experimental.Accessors;

import java.time.LocalDateTime;

/**
 * 相似题（仅保存用户确认的；作答与结果记录在二期确定）
 */
@Data
@TableName("similar_question")
@Accessors(chain = true)
public class SimilarQuestion {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long userId;

    /** 来源题目 ID */
    private Long sourceQuestionId;

    private String questionText;

    private String answer;

    private String analysis;

    private Long conversationId;

    /** 作答是否正确：0 否 / 1 是（NULL 未作答，二期启用） */
    private Integer isCorrect;

    /** 逻辑删除：0 否 / 1 是 */
    private Integer deleted;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;
}
