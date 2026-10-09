package com.xueji.agent.ai.tool;

import com.xueji.agent.service.NoteService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.chat.model.ToolContext;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

/**
 * 保存网课 AI 笔记工具单测（B28）：结果码映射、上下文 userId 透传、异常兜底
 */
@ExtendWith(MockitoExtension.class)
class SaveCourseNoteToolTest {

    @Mock
    private NoteService noteService;

    private ToolContext context() {
        return new ToolContext(Map.of("userId", 5L, "conversationId", 9L));
    }

    @Test
    void shouldMapServiceResultsToCodes() {
        SaveCourseNoteTool tool = new SaveCourseNoteTool(noteService);
        when(noteService.saveAiNoteToWorkspace(5L, 101L)).thenReturn("SAVED");
        when(noteService.saveAiNoteToWorkspace(5L, 102L)).thenReturn("ALREADY_SAVED");
        when(noteService.saveAiNoteToWorkspace(5L, 103L)).thenReturn("NOT_FOUND");

        assertThat(tool.saveCourseNote(101L, context())).isEqualTo("SAVE_SUCCESS");
        assertThat(tool.saveCourseNote(102L, context())).isEqualTo("ALREADY_SAVED");
        assertThat(tool.saveCourseNote(103L, context())).isEqualTo("SAVE_NOT_FOUND");
    }

    @Test
    void shouldReturnFailedOnException() {
        SaveCourseNoteTool tool = new SaveCourseNoteTool(noteService);
        // 课程不存在（归属校验抛业务异常）→ SAVE_FAILED，不向模型抛出
        when(noteService.saveAiNoteToWorkspace(5L, 999L)).thenThrow(new RuntimeException("网课不存在"));

        assertThat(tool.saveCourseNote(999L, context())).isEqualTo("SAVE_FAILED");
    }
}
