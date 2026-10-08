package com.xueji.agent.ai.tool;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.xueji.agent.domain.entity.Message;
import com.xueji.agent.mapper.MessageMapper;
import com.xueji.agent.service.NoteService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;

/**
 * 保存转写笔记工具（B11）：用户确认把视频转写内容收进笔记管理后由模型调用。
 * 分组名 / 标题由模型拟定并经用户确认；转写全文由服务端从最近一条转写消息确定性获取，
 * 不经模型复述（六七千字重排放大 token 且可能失真）。
 */
@Slf4j
public class CreateNoteTool {

    private final NoteService noteService;

    private final MessageMapper messageMapper;

    public CreateNoteTool(NoteService noteService, MessageMapper messageMapper) {
        this.noteService = noteService;
        this.messageMapper = messageMapper;
    }

    @Tool(description = "把当前会话最近一次的视频转写全文保存到用户的笔记管理。当用户确认保存转写内容（如回复「保存」）时调用；"
            + "调用前必须先给出你拟定的分组名与笔记标题并征得用户确认")
    public String createTranscriptNote(
            @ToolParam(description = "笔记所属分组名（由你根据内容拟定并经用户确认）；分组不存在时自动创建在根目录")
            String groupName,
            @ToolParam(description = "笔记标题（由你根据转写内容拟定并经用户确认）")
            String title,
            ToolContext toolContext) {
        Long userId = ((Number) toolContext.getContext().get("userId")).longValue();
        Long conversationId = ((Number) toolContext.getContext().get("conversationId")).longValue();
        try {
            Message transcript = messageMapper.selectOne(new QueryWrapper<Message>()
                    .eq("conversation_id", conversationId)
                    .eq("role", "assistant")
                    .eq("msg_type", "video_transcript")
                    .orderByDesc("id")
                    .last("LIMIT 1"));
            if (transcript == null || transcript.getContent() == null || transcript.getContent().isBlank()) {
                return "SAVE_NOT_FOUND";
            }
            // 保存内容优先取语音笔记草稿（B27：payload.noteDraft 由转写完成时 LLM 整理），无草稿回退转写全文
            String content = transcript.getContent();
            try {
                Object draft = cn.hutool.json.JSONUtil.parseObj(
                        transcript.getPayload() == null ? "{}" : transcript.getPayload()).get("noteDraft");
                if (draft instanceof String s && !s.isBlank()) {
                    content = s;
                }
            } catch (Exception ignored) {
                // payload 解析失败按全文保存
            }
            Long noteId = noteService.saveTranscriptNote(userId, groupName, title, content);
            log.info("保存转写笔记工具执行完成, userId={}, conversationId={}, noteId={}", userId, conversationId, noteId);
            return "SAVE_SUCCESS";
        } catch (Exception e) {
            log.error("保存转写笔记工具执行失败, userId={}, conversationId={}", userId, conversationId, e);
            return "SAVE_FAILED";
        }
    }
}
