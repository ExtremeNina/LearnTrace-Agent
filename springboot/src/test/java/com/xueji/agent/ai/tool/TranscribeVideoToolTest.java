package com.xueji.agent.ai.tool;

import com.xueji.agent.service.TranscriptionService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.chat.model.ToolContext;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * 视频转写提交工具单测（B11 分流改造）：>30 分钟返回 SUBMIT_TOO_LONG 引导课程分支，
 * 缺视频参数返回 SUBMIT_FAILED，正常提交返回 SUBMIT_OK
 */
@ExtendWith(MockitoExtension.class)
class TranscribeVideoToolTest {

    @Mock
    private TranscriptionService transcriptionService;

    @InjectMocks
    private TranscribeVideoTool tool;

    private ToolContext context(String tempPath, int durationSec) {
        Map<String, Object> map = new HashMap<>();
        map.put("userId", 5L);
        map.put("conversationId", 9L);
        if (tempPath != null) {
            map.put("videoTempPath", tempPath);
        }
        map.put("videoDurationSec", durationSec);
        return new ToolContext(map);
    }

    @Test
    void overThirtyMinutesShouldReturnTooLongAndNotSubmit() {
        String result = tool.transcribeVideo(context("C:/temp/v.mp4", 30 * 60 + 1));
        assertThat(result).startsWith("SUBMIT_TOO_LONG").contains("createCourseFromVideo");
        verify(transcriptionService, never()).submit(anyLong(), anyLong(), anyString(), anyInt());
    }

    @Test
    void missingVideoShouldReturnFailed() {
        String result = tool.transcribeVideo(context(null, 100));
        assertThat(result).startsWith("SUBMIT_FAILED");
    }

    @Test
    void withinLimitShouldSubmit() {
        String result = tool.transcribeVideo(context("C:/temp/v.mp4", 243));
        assertThat(result).isEqualTo("SUBMIT_OK");
        verify(transcriptionService).submit(5L, 9L, "C:/temp/v.mp4", 243);
    }
}
