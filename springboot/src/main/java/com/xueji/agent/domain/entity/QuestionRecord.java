package com.xueji.agent.domain.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.xueji.agent.common.UserOwned;
import lombok.Data;
import lombok.experimental.Accessors;

import java.time.LocalDateTime;

/**
 * 题目记录（统一实体；错题本 = is_wrong 的过滤视图）
 */
@Data
@TableName("question_record")
@Accessors(chain = true)
public class QuestionRecord implements UserOwned {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long userId;

    /** 题目图片在 OSS 上的对象键 */
    private String imageOssKey;

    /** OCR 原始识别文本 */
    private String ocrText;

    /** 整理后的题目文本 */
    private String questionText;

    /** 学科（AI 保存时分类，可编辑） */
    private String subject;

    /** 用户作答（OCR 识别 + 手动补充） */
    private String userAnswer;

    /** 正确答案（DeepSeek 给出，允许修改） */
    private String correctAnswer;

    /** AI 错因分析 */
    private String analysis;

    /** 是否做错：0 否 / 1 是（NULL 未判定） */
    private Integer isWrong;

    /** AI 分析状态：PENDING / SUCCESS / FAILED */
    private String aiStatus;

    /** 记录状态：正常 / 已删除 */
    private String recordStatus;

    private String userNote;

    /** 逻辑删除：0 否 / 1 是 */
    private Integer deleted;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;
}
