package com.xueji.agent.ai.tool;

import cn.hutool.json.JSONUtil;
import com.xueji.agent.domain.entity.Message;
import com.xueji.agent.mapper.MessageMapper;
import com.xueji.agent.service.TranscriptionService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.chat.model.ToolContext;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 视频转写提交工具单测（B11 分流 + 画像问询回查）：>30 分钟返回 SUBMIT_TOO_LONG 引导课程分支，
 * 缺视频参数回查会话消息 payload，找不到可用视频返回 SUBMIT_FAILED，正常提交返回 SUBMIT_OK
 */
@ExtendWith(MockitoExtension.class)
class TranscribeVideoToolTest {

    @Mock
    private TranscriptionService transcriptionService;

    @Mock
    private MessageMapper messageMapper;

    private TranscribeVideoTool tool;

    @BeforeEach
    void setUp() {
        tool = new TranscribeVideoTool(transcriptionService, messageMapper);
    }

    private ToolContext context(String tempPath, Integer durationSec) {
        Map<String, Object> map = new HashMap<>();
        map.put("userId", 5L);
        map.put("conversationId", 9L);
        if (tempPath != null) {
            map.put("videoTempPath", tempPath);
        }
        if (durationSec != null) {
            map.put("videoDurationSec", durationSec);
        }
        return new ToolContext(map);
    }

    @Test
    void overThirtyMinutesShouldReturnTooLongAndNotSubmit(@TempDir Path dir) throws Exception {
        Path video = Files.createTempFile(dir, "v-", ".mp4");
        String result = tool.transcribeVideo(context(video.toString(), 30 * 60 + 1));
        assertThat(result).startsWith("SUBMIT_TOO_LONG").contains("createCourseFromVideo");
        verify(transcriptionService, never()).submit(anyLong(), anyLong(), anyString(), anyInt());
    }

    @Test
    void missingVideoEverywhereShouldReturnFailed() {
        when(messageMapper.selectOne(any())).thenReturn(null);
        String result = tool.transcribeVideo(context(null, null));
        assertThat(result).startsWith("SUBMIT_FAILED");
    }

    @Test
    void fallbackShouldReadPayloadFromLatestVideoMessage(@TempDir Path dir) throws Exception {
        // 回合 2 场景：ToolContext 无视频参数 → 从会话最近视频消息 payload 回查（画像问询打断修复）
        Path video = Files.createTempFile(dir, "v-", ".mp4");
        Message message = new Message().setId(2L).setMsgType("video").setPayload(JSONUtil.createObj()
                .set("videoTempPath", video.toString())
                .set("videoDurationSec", 243)
                .toString());
        when(messageMapper.selectOne(any())).thenReturn(message);

        String result = tool.transcribeVideo(context(null, null));
        assertThat(result).isEqualTo("SUBMIT_OK");
        verify(transcriptionService).submit(5L, 9L, video.toString(), 243);
    }

    @Test
    void stalePathShouldNotSubmit(@org.junit.jupiter.api.io.TempDir Path dir) {
        // payload 有路径但文件已清理 → 引导重新上传
        Message message = new Message().setId(2L).setMsgType("video").setPayload(JSONUtil.createObj()
                .set("videoTempPath", dir.resolve("gone.mp4").toString())
                .set("videoDurationSec", 243)
                .toString());
        when(messageMapper.selectOne(any())).thenReturn(message);
        String result = tool.transcribeVideo(context(null, null));
        assertThat(result).startsWith("SUBMIT_FAILED").contains("重新上传");
    }

    @Test
    void withinLimitShouldSubmit(@TempDir Path dir) throws Exception {
        Path video = Files.createTempFile(dir, "v-", ".mp4");
        String result = tool.transcribeVideo(context(video.toString(), 243));
        assertThat(result).isEqualTo("SUBMIT_OK");
        verify(transcriptionService).submit(5L, 9L, video.toString(), 243);
    }
}
