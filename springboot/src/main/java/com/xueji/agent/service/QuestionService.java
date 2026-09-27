package com.xueji.agent.service;

import com.xueji.agent.domain.dto.QuestionUpdateDto;
import com.xueji.agent.domain.entity.QuestionRecord;
import com.xueji.agent.domain.vo.PageVO;

/**
 * 题目记录：拍照题目的保存、分页列表、详情、编辑与删除
 */
public interface QuestionService {

    /**
     * 把会话中最近一次拍照识别的题目保存到用户拍照记录。
     * questionText / correctAnswer / analysis 由模型整理后传入（保存前已清洗元叙述）。
     *
     * @return true 保存成功；false 当前会话没有可保存的拍照题目或内容不完整
     */
    boolean saveFromConversation(Long userId, Long conversationId,
                                 String questionText, String correctAnswer, String analysis);

    /**
     * 当前用户的题目分页列表（新记录在前），可按日期（yyyy-MM-dd）筛选
     */
    PageVO<QuestionRecord> listByUser(Long userId, String date, int page, int size);

    /**
     * 题目详情（校验归属，不存在或非本人返回 404）
     */
    QuestionRecord getDetail(Long userId, Long id);

    /**
     * 编辑题目（仅更新提供的字段），返回更新后的记录
     */
    QuestionRecord updateByUser(Long userId, Long id, QuestionUpdateDto dto);

    /**
     * 删除题目（逻辑删除）
     */
    void deleteByUser(Long userId, Long id);
}
