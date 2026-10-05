package com.xueji.agent.service;

import java.util.Map;

/**
 * 首页仪表盘聚合（B25 工单 3）：继续学习 / 最近学习 / 今日复习 / 学习数据 / 本周统计
 */
public interface HomeService {

    /**
     * 首页一次性聚合：nickname、continueCourse（最近一门有播放记录的网课）、
     * recentCourses（最近学习 top4）、todayQueue（今日队列前 3 张作标签）、
     * stats（学习数据四格）、week（本周只读统计）。
     * 统计口径与每日简报 / get_learning_status 共用（LearningStatsService）
     */
    Map<String, Object> overview(Long userId);
}
