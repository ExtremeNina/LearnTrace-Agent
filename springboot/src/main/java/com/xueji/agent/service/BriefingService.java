package com.xueji.agent.service;

import com.xueji.agent.domain.vo.BriefingVO;

import java.util.Map;

/**
 * 每日学习简报：惰性生成（当天首次访问触发 LLM，落库缓存幂等）+ 学习状态统计快照
 * （统计快照同时供 Agent 工具 get_learning_status 使用）
 */
public interface BriefingService {

    /**
     * 今日简报：按（用户 × 会话 × 日期）幂等——已有则读缓存；
     * 存在未绑定的当日旧数据则补绑定到该会话；同日已有其他归属的简报则复用内容（不重复调用 LLM）。
     *
     * @param conversationId 归属会话，可为空（如新对话尚未创建时）
     */
    BriefingVO getTodayBriefing(Long userId, Long conversationId);

    /** 强制刷新：重新聚合统计并重新生成（用户 × 会话 × 日期）的今日简报 */
    BriefingVO refreshTodayBriefing(Long userId, Long conversationId);

    /** 查询某会话的全部简报（按生成时间升序），供切换会话时恢复简报在消息流中的位置 */
    java.util.List<BriefingVO> listByConversation(Long userId, Long conversationId);

    /** 学习状态统计快照（本周复习 / 薄弱卡 / 新增笔记 / 网课进度），Agent 工具与简报共用 */
    Map<String, Object> getStatsSnapshot(Long userId);
}
