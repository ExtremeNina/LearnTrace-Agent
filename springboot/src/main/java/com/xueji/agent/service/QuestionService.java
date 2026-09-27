package com.xueji.agent.service;

import com.xueji.agent.domain.entity.QuestionRecord;

import java.util.List;

/**
 * 题目记录：拍照题目的保存、列表与详情
 */
public interface QuestionService {

    /**
     * 把会话中最近一次拍照识别的题目保存到用户拍照记录
     *
     * @return true 保存成功；false 当前会话没有可保存的拍照题目
     */
    boolean saveFromConversation(Long userId, Long conversationId);

    /**
     * 当前用户已保存的题目列表（新记录在前）
     */
    List<QuestionRecord> listByUser(Long userId);

    /**
     * 题目详情（校验归属，不存在或非本人返回 404）
     */
    QuestionRecord getDetail(Long userId, Long id);
}
