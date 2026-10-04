package com.xueji.agent.ai.tool;

import com.xueji.agent.service.TranscriptionService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;

/**
 * 对话视频转写提交工具（B11）：用户上传视频且意图与内容相关时由模型调用。
 * 任务异步执行（占位消息 + 进度推送回流），工具秒回不阻塞回合；
 * videoTempPath / videoDurationSec 由对话链路经 ToolContext 传入（客户端上传接口产出）。
 */
@Slf4j
public class TranscribeVideoTool {

    private final TranscriptionService transcriptionService;

    public TranscribeVideoTool(TranscriptionService transcriptionService) {
        this.transcriptionService = transcriptionService;
    }

    @Tool(description = "提交当前会话中用户上传视频的语音转写任务。当用户上传了视频且意图与视频内容相关（想转写、总结、提问内容、保存为笔记）时调用。"
            + "任务异步执行（通常 2~5 分钟），转写完成后系统会把全文自动发进对话；提交成功后只需告知用户转写已开始")
    public String transcribeVideo(ToolContext toolContext) {
        Long userId = ((Number) toolContext.getContext().get("userId")).longValue();
        Long conversationId = ((Number) toolContext.getContext().get("conversationId")).longValue();
        Object tempPathObj = toolContext.getContext().get("videoTempPath");
        Object durationObj = toolContext.getContext().get("videoDurationSec");
        if (tempPathObj == null || durationObj == null) {
            return "SUBMIT_FAILED: 当前会话没有待转写的视频";
        }
        try {
            Long messageId = transcriptionService.submit(userId, conversationId,
                    tempPathObj.toString(), ((Number) durationObj).intValue());
            log.info("视频转写工具提交成功, userId={}, conversationId={}, messageId={}", userId, conversationId, messageId);
            return "SUBMIT_OK";
        } catch (Exception e) {
            log.error("视频转写工具提交失败, userId={}, conversationId={}", userId, conversationId, e);
            return "SUBMIT_FAILED: " + e.getMessage();
        }
    }
}
