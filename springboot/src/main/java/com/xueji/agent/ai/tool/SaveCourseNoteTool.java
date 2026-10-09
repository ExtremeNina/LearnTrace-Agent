package com.xueji.agent.ai.tool;

import com.xueji.agent.service.NoteService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;

/**
 * 保存网课 AI 笔记到笔记管理工具（B28）：网课流水线生成的 AI 笔记默认不在笔记管理展示（save_status=0），
 * 仅当用户明确要求保存时由模型调用本工具把该课程 AI 笔记置为已保存（save_status=1）。
 * 幂等：已保存过重复调用返回 ALREADY_SAVED，不重复处理。
 */
@Slf4j
public class SaveCourseNoteTool {

    private final NoteService noteService;

    public SaveCourseNoteTool(NoteService noteService) {
        this.noteService = noteService;
    }

    @Tool(description = "把某个网课的 AI 笔记保存到用户的笔记管理。仅当用户明确要求保存某网课 / 某视频的 AI 笔记时调用；"
            + "无法确定用户指的是哪个网课时先向用户追问课程，不要猜测")
    public String saveCourseNote(
            @ToolParam(description = "要保存 AI 笔记的网课 ID") Long courseId,
            ToolContext toolContext) {
        Long userId = ((Number) toolContext.getContext().get("userId")).longValue();
        try {
            String result = noteService.saveAiNoteToWorkspace(userId, courseId);
            log.info("保存网课 AI 笔记工具执行完成, userId={}, courseId={}, result={}", userId, courseId, result);
            return switch (result) {
                case "SAVED" -> "SAVE_SUCCESS";
                case "ALREADY_SAVED" -> "ALREADY_SAVED";
                default -> "SAVE_NOT_FOUND";
            };
        } catch (Exception e) {
            log.error("保存网课 AI 笔记工具执行失败, userId={}, courseId={}", userId, courseId, e);
            return "SAVE_FAILED";
        }
    }
}
