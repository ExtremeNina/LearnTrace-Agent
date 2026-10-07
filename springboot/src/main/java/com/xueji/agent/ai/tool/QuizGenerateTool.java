package com.xueji.agent.ai.tool;

import com.xueji.agent.ai.QuizAgentService;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;

/**
 * QuizAgent 委派工具（B26 阶段 4 agent-as-tool）：
 * 主对话 Agent 按意图委派无状态 QuizAgent 基于内部资料出练习题，题目自动落题目管理
 */
public class QuizGenerateTool {

    private final QuizAgentService quizAgentService;

    public QuizGenerateTool(QuizAgentService quizAgentService) {
        this.quizAgentService = quizAgentService;
    }

    @Tool(description = "委派 QuizAgent 基于用户已学课程内容（内容文档 + 转写）出练习题并自动存入题目管理。"
            + "当用户要求基于某门课出练习题 / 出一组测验时调用（不要自己编题）。"
            + "返回题目预览；用户如需逐题讲解可在预览基础上继续回答")
    public String generatePractice(
            @ToolParam(description = "课程名关键词（可省略，省略时取用户最近处理完成的网课）", required = false)
            String courseHint,
            @ToolParam(description = "题目数量，5 或 10，省略时默认 5", required = false)
            Integer count,
            ToolContext toolContext) {
        Long userId = (Long) toolContext.getContext().get("userId");
        try {
            return quizAgentService.generate(userId, courseHint, count);
        } catch (Exception e) {
            org.slf4j.LoggerFactory.getLogger(QuizGenerateTool.class)
                    .error("QuizAgent 出题失败, userId={}", userId, e);
            return "QUIZ_FAILED: " + e.getMessage();
        }
    }
}
