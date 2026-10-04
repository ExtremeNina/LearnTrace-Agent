package com.xueji.agent.ai.tool;

import com.xueji.agent.domain.entity.Message;
import com.xueji.agent.mapper.MessageMapper;
import com.xueji.agent.service.NoteService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.chat.model.ToolContext;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

/**
 * 保存转写笔记工具单测（B11）：取最近转写消息全文落库，三分支结果码语义
 */
@ExtendWith(MockitoExtension.class)
class CreateNoteToolTest {

    @Mock
    private NoteService noteService;

    @Mock
    private MessageMapper messageMapper;

    @InjectMocks
    private CreateNoteTool tool;

    private ToolContext context() {
        return new ToolContext(Map.of("userId", 5L, "conversationId", 9L));
    }

    @Test
    void shouldSaveLatestTranscriptAndReturnSuccess() {
        Message transcript = new Message().setId(2L).setRole("assistant")
                .setMsgType("video_transcript").setContent("**视频转写完成**\n\n[00:00] 内容");
        when(messageMapper.selectOne(any())).thenReturn(transcript);
        when(noteService.saveTranscriptNote(eq(5L), eq("明日方舟"), eq("铁花飞落"), anyString())).thenReturn(88L);

        String result = tool.createTranscriptNote("明日方舟", "铁花飞落", context());

        assertThat(result).isEqualTo("SAVE_SUCCESS");
    }

    @Test
    void shouldReturnNotFoundWhenNoTranscript() {
        when(messageMapper.selectOne(any())).thenReturn(null);
        String result = tool.createTranscriptNote("分组", "标题", context());
        assertThat(result).isEqualTo("SAVE_NOT_FOUND");
    }

    @Test
    void shouldReturnFailedWhenServiceThrows() {
        Message transcript = new Message().setId(2L).setMsgType("video_transcript").setContent("内容");
        when(messageMapper.selectOne(any())).thenReturn(transcript);
        when(noteService.saveTranscriptNote(org.mockito.ArgumentMatchers.anyLong(), anyString(), anyString(), anyString()))
                .thenThrow(new RuntimeException("db down"));

        String result = tool.createTranscriptNote("分组", "标题", context());
        assertThat(result).isEqualTo("SAVE_FAILED");
    }
}
