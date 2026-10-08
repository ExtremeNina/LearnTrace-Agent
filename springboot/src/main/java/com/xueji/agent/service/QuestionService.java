package com.xueji.agent.service;

import com.xueji.agent.domain.dto.QuestionUpdateDto;
import com.xueji.agent.domain.vo.PageVO;
import com.xueji.agent.domain.vo.QuestionItemVO;

/**
 * 题目记录：拍照题目的保存、分页列表、详情、编辑与删除；
 * AI 生成的相似题入 similar_question 表，在列表中与拍照记录合并展示（source 区分）
 */
public interface QuestionService {

    /**
     * 把会话中的题目保存到用户题目记录。
     * questionText / correctAnswer / analysis 由模型整理后传入（保存前已清洗元叙述）。
     *
     * @param source           题目来源：photo = 会话中最近一次拍照识别的题目（自动关联题目图片）；
     *                         text = 纯文字题目（RAG 相似题、用户手打的题），入 similar_question 表
     * @param sourceQuestionId 相似题的来源题目 ID（基于 rag_search 召回的某道题生成时提供，可空）
     * @return true 保存成功；false 当前会话没有可保存的拍照题目或内容不完整
     */
    boolean saveFromConversation(Long userId, Long conversationId, String source, Long sourceQuestionId,
                                 String questionText, String correctAnswer, String analysis, String subject);

    /**
     * 当前用户的题目分页列表：拍照题目与 AI 相似题合并（相似题 source = similar_ai），
     * 可按日期（yyyy-MM-dd）、学科与题干关键词筛选
     */
    PageVO<QuestionItemVO> listByUser(Long userId, String date, String subject, String keyword, int page, int size);

    /**
     * 题目详情（校验归属，不存在或非本人返回 404）
     *
     * @param source photo / similar_ai，缺省按拍照题目处理
     */
    QuestionItemVO getDetail(Long userId, Long id, String source);

    /**
     * 编辑题目（仅更新提供的字段），返回更新后的记录
     */
    QuestionItemVO updateByUser(Long userId, Long id, String source, QuestionUpdateDto dto);

    /**
     * 删除题目（逻辑删除）
     */
    void deleteByUser(Long userId, Long id, String source);
}
