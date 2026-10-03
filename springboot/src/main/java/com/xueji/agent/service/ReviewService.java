package com.xueji.agent.service;

import com.xueji.agent.domain.vo.ReviewCardVO;

import java.util.List;
import java.util.Map;

/**
 * 复习系统：加卡 / 今日队列 / 评分调度 / 统计
 */
public interface ReviewService {

    /** 加入复习：校验来源实体归属与存在，去重（已在队列 / 已移出则恢复） */
    ReviewCardVO addCard(Long userId, String cardType, Long refId);

    /** 今日队列：due_at 不晚于今天末尾的卡，按到期排序，上限 20 张 */
    List<ReviewCardVO> todayQueue(Long userId);

    /** 复习评分：按调度算法更新卡片状态并写评分流水 */
    ReviewCardVO review(Long userId, Long cardId, int grade);

    /** 统计：dueCount 今日待复习 / total 队列总数 / reviewedToday 今日已复习 */
    Map<String, Object> stats(Long userId);

    /** 移出复习队列（逻辑删除，不影响来源实体） */
    void removeCard(Long userId, Long cardId);

    /** 来源实体删除时的级联：移出对应复习卡 */
    void removeBySource(Long userId, String cardType, Long refId);

    /** 是否已在复习队列 */
    boolean inQueue(Long userId, String cardType, Long refId);
}
