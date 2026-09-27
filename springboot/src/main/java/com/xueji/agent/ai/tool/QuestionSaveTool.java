package com.xueji.agent.ai.tool;

import com.xueji.agent.service.QuestionService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.annotation.Tool;

/**
 * 保存题目工具：用户在对话中表示要保存题目 / 错题时由模型调用。
 * userId / conversationId 经 ToolContext 从调用链路传入，工具自身不感知会话；
 * 数据取自 message 表事实源，返回结果码由模型按提示词组织话术。
 */
@Slf4j
public class QuestionSaveTool {

    private final QuestionService questionService;

    public QuestionSaveTool(QuestionService questionService) {
        this.questionService = questionService;
    }

    @Tool(description = "把当前会话中最近一次拍照识别的题目保存到用户的拍照记录。当用户表达要保存题目、保存错题、收藏题目时调用")
    public String saveQuestion(ToolContext toolContext) {
        Long userId = ((Number) toolContext.getContext().get("userId")).longValue();
        Long conversationId = ((Number) toolContext.getContext().get("conversationId")).longValue();
        try {
            boolean saved = questionService.saveFromConversation(userId, conversationId);
            log.info("保存题目工具执行完成, userId={}, conversationId={}, saved={}", userId, conversationId, saved);
            return saved ? "SAVE_SUCCESS" : "SAVE_NOT_FOUND";
        } catch (Exception e) {
            log.error("保存题目工具执行失败, userId={}, conversationId={}", userId, conversationId, e);
            return "SAVE_FAILED";
        }
    }
}
