package com.xueji.agent.ai.tool;

import cn.hutool.json.JSONUtil;
import com.xueji.agent.service.LearningStatsService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;

/**
 * 学习状态查询工具（路线图 P0-2 / B07 起步）：
 * 返回用户当前学习状态的统计快照（待复习数、队列总数、本周复习与生疏次数、
 * 本周新增笔记、网课进度、本周生疏的薄弱卡），供 LLM 回答"我最近哪块最弱"类问题。
 */
@Slf4j
public class LearningStatusTool {

    private final LearningStatsService learningStatsService;

    public LearningStatusTool(LearningStatsService learningStatsService) {
        this.learningStatsService = learningStatsService;
    }

    @Tool(name = "get_learning_status", description = "获取用户当前的学习状态统计：待复习卡片数、复习队列总数、今日已复习、本周复习次数与生疏次数、本周新增笔记数、网课完成进度、本周生疏的薄弱卡列表。在回答用户的学习状态、薄弱知识点、复习建议类问题前调用")
    public String getLearningStatus(
            @ToolParam(description = "统计范围天数；默认 7（本周）。传 30 可看本月", required = false)
            Integer days,
            ToolContext toolContext) {
        Long userId = ((Number) toolContext.getContext().get("userId")).longValue();
        try {
            // days 参数当前仅影响表述口径（快照固定为本周），留作扩展位
            Object snapshot = learningStatsService.getStatsSnapshot(userId);
            log.info("学习状态工具执行完成, userId={}", userId);
            return JSONUtil.toJsonStr(snapshot);
        } catch (Exception e) {
            log.error("学习状态工具执行失败, userId={}", userId, e);
            return "LEARNING_STATUS_FAILED";
        }
    }
}
