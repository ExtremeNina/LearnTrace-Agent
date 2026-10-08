package com.xueji.agent.service.impl;

import com.xueji.agent.ai.tool.AsrSegment;
import com.xueji.agent.ai.tool.QwenAsrTool;
import com.xueji.agent.domain.entity.Message;
import com.xueji.agent.exception.BusinessException;
import com.xueji.agent.mapper.MessageMapper;
import com.xueji.agent.utils.AliUploadUtils;
import com.xueji.agent.utils.MediaUtils;
import com.xueji.agent.ws.AgentEventPushService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.memory.ChatMemoryRepository;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 对话视频转写任务单测（B11）：分片进度推进、结果消息内容格式、失败降级文案、
 * 记忆追加、临时路径校验。FFmpeg 命令与 OSS 上传均为静态/实例 Mock，不触发真实外调。
 */
@ExtendWith(MockitoExtension.class)
class TranscriptionServiceImplTest {

    @Mock
    private MessageMapper messageMapper;

    @Mock
    private AliUploadUtils aliUploadUtils;

    @Mock
    private QwenAsrTool qwenAsrTool;

    @Mock
    private AgentEventPushService pushService;

    @Mock
    private ChatMemoryRepository chatMemoryRepository;

    @Mock
    private ChatClient generationChatClient;

    @Mock
    private ChatClient.ChatClientRequestSpec promptSpec;

    @InjectMocks
    private TranscriptionServiceImpl service;

    @Captor
    private ArgumentCaptor<Message> messageCaptor;

    private Path newTempVideo() throws Exception {
        Path video = Files.createTempFile("xj-test-video-", ".mp4");
        Files.writeString(video, "fake");
        return video;
    }

    private Message placeholder(long id) {
        return new Message().setId(id).setConversationId(9L).setRole("assistant")
                .setMsgType("video_transcript")
                .setPayload("{\"status\":\"processing\",\"done\":0,\"total\":0}");
    }

    @Test
    void runTaskShouldPushChunkedProgressAndFinishWithTranscript() throws Exception {
        ReflectionTestUtils.setField(service, "maxMessages", 100);
        Path video = newTempVideo();
        Message row = placeholder(1L);
        when(messageMapper.selectById(1L)).thenReturn(row);
        when(messageMapper.updateById(any(Message.class))).thenReturn(1);
        when(aliUploadUtils.uploadLocalFile(any(Path.class), anyString())).thenReturn("https://oss/fake");
        // 700s → 3 片，每片返回一个句级分段（偏移 60s，验证时间戳合并）
        when(qwenAsrTool.transcribeSegments(anyString())).thenAnswer(inv -> List.of(
                new AsrSegment(5000, 8000, "第一句")));

        try (MockedStatic<MediaUtils> media = mockStatic(MediaUtils.class)) {
            service.runTask(5L, 9L, 1L, video, 700);

            // 分片切割命令按 280s 步进执行 3 次
            media.verify(() -> MediaUtils.run(any(String[].class)), times(3));
        }

        // 最终消息：转写全文（含头部引导与 [mm:ss] 行，末片偏移 560s + 5s = 09:25）+ payload status=done
        // updateById 共 5 次：初始进度 + 3 次分片进度 + 完成落定
        verify(messageMapper, times(5)).updateById(messageCaptor.capture());
        Message last = messageCaptor.getAllValues().get(messageCaptor.getAllValues().size() - 1);
        assertThat(last.getContent()).startsWith("**视频转写完成**（时长 11:40，共 3 段，约 9 字）");
        assertThat(last.getContent()).contains("[09:25] 第一句");
        assertThat(last.getPayload()).contains("\"status\":\"done\"");

        // 进度事件：0/3 起步，逐片推进
        verify(pushService, times(5)).pushToUser(eq(5L), any());
        // 转写全文追加进会话记忆
        verify(chatMemoryRepository).saveAll(eq("9"), any());
        org.mockito.Mockito.verify(chatMemoryRepository, org.mockito.Mockito.atLeastOnce())
                .findByConversationId("9");
    }

    @Test
    @SuppressWarnings("unchecked")
    void runTaskShouldAttachVoiceNoteDraftWhenLlmSucceeds() throws Exception {
        ReflectionTestUtils.setField(service, "maxMessages", 100);
        Path video = newTempVideo();
        Message row = placeholder(1L);
        when(messageMapper.selectById(1L)).thenReturn(row);
        when(messageMapper.updateById(any(Message.class))).thenReturn(1);
        when(aliUploadUtils.uploadLocalFile(any(Path.class), anyString())).thenReturn("https://oss/fake");
        when(qwenAsrTool.transcribeSegments(anyString())).thenAnswer(inv -> List.of(
                new AsrSegment(5000, 8000, "第一句")));

        ChatClient.CallResponseSpec callSpec = org.mockito.Mockito.mock(ChatClient.CallResponseSpec.class);
        when(generationChatClient.prompt()).thenReturn(promptSpec);
        when(promptSpec.system(anyString())).thenReturn(promptSpec);
        when(promptSpec.user(anyString())).thenReturn(promptSpec);
        when(promptSpec.call()).thenReturn(callSpec);
        when(callSpec.content()).thenReturn("# 整理稿");

        try (MockedStatic<MediaUtils> ignored = mockStatic(MediaUtils.class)) {
            service.runTask(5L, 9L, 1L, video, 100);
        }

        verify(messageMapper, org.mockito.Mockito.atLeastOnce()).updateById(messageCaptor.capture());
        Message last = messageCaptor.getAllValues().get(messageCaptor.getAllValues().size() - 1);
        assertThat(last.getContent()).contains("## 📝 语音笔记（草稿）").contains("# 整理稿");
        assertThat(last.getPayload()).contains("noteDraft").contains("\"status\":\"done\"");
        // 记忆追加的仍是转写原文（草稿属展示层，不进记忆）
        verify(chatMemoryRepository).saveAll(eq("9"), any());
    }

    @Test
    void runTaskShouldMarkFailedWhenAsrFails() throws Exception {
        ReflectionTestUtils.setField(service, "maxMessages", 100);
        Path video = newTempVideo();
        Message row = placeholder(1L);
        when(messageMapper.selectById(1L)).thenReturn(row);
        when(messageMapper.updateById(any(Message.class))).thenReturn(1);
        when(aliUploadUtils.uploadLocalFile(any(Path.class), anyString())).thenReturn("https://oss/fake");
        when(qwenAsrTool.transcribeSegments(anyString()))
                .thenThrow(new IllegalStateException("未识别出音频中的语音内容"));

        try (MockedStatic<MediaUtils> ignored = mockStatic(MediaUtils.class)) {
            service.runTask(5L, 9L, 1L, video, 100);
        }

        verify(messageMapper, org.mockito.Mockito.atLeastOnce()).updateById(messageCaptor.capture());
        Message last = messageCaptor.getAllValues().get(messageCaptor.getAllValues().size() - 1);
        assertThat(last.getContent()).contains("视频转写失败").contains("未识别出音频中的语音内容");
        assertThat(last.getPayload()).contains("\"status\":\"failed\"");
        // 不追加记忆
        verify(chatMemoryRepository, times(0)).saveAll(anyString(), any());
    }

    @Test
    void submitShouldRejectPathOutsideTempDir() {
        assertThatThrownBy(() -> service.submit(5L, 9L, "C:/Users/Nina/Desktop/x.mp4", 100))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("非法的视频文件路径");
    }

    @Test
    void submitShouldRejectMissingFile() {
        String missing = Path.of(System.getProperty("java.io.tmpdir"), "xj-no-such-video.mp4").toString();
        assertThatThrownBy(() -> service.submit(5L, 9L, missing, 100))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("重新上传");
    }

    @Test
    void assembleTranscriptShouldFormatHourTimestamps() {
        String text = service.assembleTranscript(List.of(
                new AsrSegment(0, 3000, "开场"),
                new AsrSegment(3_665_000, 3_670_000, "结尾")), 3_670);
        assertThat(text).contains("[00:00] 开场");
        assertThat(text).contains("[1:01:05] 结尾");
        assertThat(text).contains("**视频转写完成**（时长 1:01:10，共 2 段，约 4 字）");
    }
}
