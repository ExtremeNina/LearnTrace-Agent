package com.xueji.agent.service;

import com.xueji.agent.domain.vo.QuizPickVO;

import java.util.List;

/**
 * 练习 / 测验模式（路线图 P0-3）：从题库随机抽题组卷，交卷后错题经批量加卡沉淀进复习队列
 */
public interface QuizService {

    /**
     * 组卷抽题：按学科 / 时间段 / 来源筛选题库（拍照题目 + AI 相似题），随机抽 count 道
     *
     * @param userId  当前用户
     * @param count   抽题数量（1~20）
     * @param subject 学科（空 = 不限）
     * @param period  时间段：7d / 30d / all
     * @param sources 来源列表：question / similar（空 = 全部）
     */
    List<QuizPickVO> pick(Long userId, int count, String subject, String period, List<String> sources);
}
