package com.xueji.agent.service;

/**
 * 学习时长（B25 首页「今日学习时长」）：前端心跳累计，一天一行
 */
public interface StudyTimeService {

    /**
     * 心跳上报：把本次在站秒数累计到今天（单次截断到 1~300 秒，防异常值刷量）
     */
    void heartbeat(Long userId, Integer seconds);

    /**
     * 今日累计分钟（向下取整，无记录为 0）
     */
    int todayMinutes(Long userId);
}
