package com.xueji.agent.service;

import com.xueji.agent.domain.vo.BriefingVO;

import java.util.Map;

/**
 * 每日学习简报：惰性生成（当天首次访问触发 LLM，落库缓存幂等）+ 学习状态统计快照
 * （统计快照同时供 Agent 工具 get_learning_status 使用）
 */
public interface BriefingService {

    /** 今日简报：当天已有则读缓存，否则聚合统计并生成 */
    BriefingVO getTodayBriefing(Long userId);

    /** 强制刷新：重新聚合统计并重新生成今日简报 */
    BriefingVO refreshTodayBriefing(Long userId);

    /** 学习状态统计快照（本周复习 / 薄弱卡 / 新增笔记 / 网课进度），Agent 工具与简报共用 */
    Map<String, Object> getStatsSnapshot(Long userId);
}
