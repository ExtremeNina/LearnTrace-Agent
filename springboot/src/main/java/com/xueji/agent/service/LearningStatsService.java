package com.xueji.agent.service;

import java.util.Map;

/**
 * 学习状态统计：聚合复习 / 错题 / 笔记 / 网课数据为结构化快照。
 * 独立于 BriefingService（无 LLM 依赖），供每日简报与 Agent 工具 get_learning_status 共用
 */
public interface LearningStatsService {

    /**
     * 学习状态统计快照：dueToday / totalCards / reviewedThisWeek / againThisWeek /
     * notesCreatedThisWeek / coursesTotal / coursesSuccess / weakCards（本周生疏的薄弱卡，上限 5）
     */
    Map<String, Object> getStatsSnapshot(Long userId);
}
