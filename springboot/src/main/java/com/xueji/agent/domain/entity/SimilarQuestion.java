package com.xueji.agent.domain.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.xueji.agent.common.UserOwned;
import lombok.Data;
import lombok.experimental.Accessors;

import java.time.LocalDateTime;

/**
 * 相似题（仅保存用户确认的；作答与结果记录在二期确定）。
 * 在拍照记录列表中与题目记录合并展示，标注「AI 生成」。
 */
@Data
@TableName("similar_question")
@Accessors(chain = true)
public class SimilarQuestion implements UserOwned {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long userId;

    /** 来源题目（对话流保存时为空） */
    private Long sourceQuestionId;

    private String questionText;

    private String answer;

    private String analysis;

    /** 学科（AI 保存时分类，可编辑） */
    private String subject;

    private Long conversationId;

    /** 作答是否正确：0 否 / 1 是（NULL 未作答，二期启用） */
    private Integer isCorrect;

    /** 逻辑删除：0 否 / 1 是 */
    private Integer deleted;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;
}
