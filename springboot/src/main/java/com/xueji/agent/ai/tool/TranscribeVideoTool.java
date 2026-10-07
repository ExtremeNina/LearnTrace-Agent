package com.xueji.agent.ai.tool;

import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.xueji.agent.domain.entity.Message;
import com.xueji.agent.mapper.MessageMapper;
import com.xueji.agent.service.TranscriptionService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.annotation.Tool;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 对话视频转写提交工具（B11）：用户上传视频且意图与内容相关时由模型调用。
 * 任务异步执行（占位消息 + 进度推送回流），工具秒回不阻塞回合。
 * 视频参数解析：优先 ToolContext（当前回合附带）；被问询打断等后续回合从会话最近一条视频消息 payload 回查
 */
@Slf4j
public class TranscribeVideoTool {

    private final TranscriptionService transcriptionService;
    private final MessageMapper messageMapper;

    public TranscribeVideoTool(TranscriptionService transcriptionService, MessageMapper messageMapper) {
        this.transcriptionService = transcriptionService;
        this.messageMapper = messageMapper;
    }

    @Tool(description = "提交当前会话中用户上传视频的语音转写任务（仅语音转写，适用于 ≤30 分钟的视频，默认方针）。当用户上传了视频且意图与视频内容相关（想转写、总结、提问内容、保存为笔记）时调用。"
            + "任务异步执行（通常 2~5 分钟），转写完成后系统会把全文自动发进对话；提交成功后只需告知用户转写已开始，并说明如需完整网课处理可回复「做成课程」")
    public String transcribeVideo(ToolContext toolContext) {
        Long userId = ((Number) toolContext.getContext().get("userId")).longValue();
        Long conversationId = ((Number) toolContext.getContext().get("conversationId")).longValue();

        String videoTempPath = contextStr(toolContext, "videoTempPath");
        Integer durationSec = contextInt(toolContext, "videoDurationSec");
        if (videoTempPath == null || durationSec == null) {
            // 后续回合回查：会话最近一条用户视频消息的 payload（画像问询等打断场景）
            Message videoMessage = messageMapper.selectOne(new QueryWrapper<Message>()
                    .eq("conversation_id", conversationId)
                    .eq("role", "user")
                    .eq("msg_type", "video")
                    .orderByDesc("id")
                    .last("LIMIT 1"));
            if (videoMessage != null && videoMessage.getPayload() != null && !videoMessage.getPayload().isBlank()) {
                try {
                    JSONObject payload = JSONUtil.parseObj(videoMessage.getPayload());
                    videoTempPath = payload.getStr("videoTempPath", null);
                    durationSec = payload.getInt("videoDurationSec", null);
                } catch (Exception e) {
                    log.warn("视频消息 payload 解析失败, messageId={}", videoMessage.getId());
                }
            }
        }
        if (videoTempPath == null || durationSec == null || !Files.exists(Path.of(videoTempPath))) {
            return "SUBMIT_FAILED: 当前会话没有可转写的视频（视频可能已处理完成或被清理），请让用户重新上传";
        }
        if (durationSec > TranscriptionService.MAX_TRANSCRIBE_SEC) {
            // >30 分钟：轻量转写不适用，引导 LLM 改调课程流水线工具
            return "SUBMIT_TOO_LONG: 视频时长超过 30 分钟（" + durationSec / 60 + " 分钟），请改用 createCourseFromVideo 工具按网课处理";
        }
        try {
            Long messageId = transcriptionService.submit(userId, conversationId, videoTempPath, durationSec);
            log.info("视频转写工具提交成功, userId={}, conversationId={}, messageId={}", userId, conversationId, messageId);
            return "SUBMIT_OK";
        } catch (Exception e) {
            log.error("视频转写工具提交失败, userId={}, conversationId={}", userId, conversationId, e);
            return "SUBMIT_FAILED: " + e.getMessage();
        }
    }

    private static String contextStr(ToolContext toolContext, String key) {
        Object value = toolContext.getContext().get(key);
        return value == null ? null : value.toString();
    }

    private static Integer contextInt(ToolContext toolContext, String key) {
        Object value = toolContext.getContext().get(key);
        return value instanceof Number number ? number.intValue() : null;
    }
}
