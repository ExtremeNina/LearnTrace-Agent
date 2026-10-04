package com.xueji.agent.service.impl;

import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.xueji.agent.ai.tool.AsrSegment;
import com.xueji.agent.ai.tool.QwenAsrTool;
import com.xueji.agent.domain.entity.Message;
import com.xueji.agent.domain.vo.ChatEvent;
import com.xueji.agent.exception.BusinessException;
import com.xueji.agent.mapper.MessageMapper;
import com.xueji.agent.service.TranscriptionService;
import com.xueji.agent.utils.AliUploadUtils;
import com.xueji.agent.utils.MediaUtils;
import com.xueji.agent.ws.AgentEventPushService;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.memory.ChatMemoryRepository;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ThreadPoolExecutor;

/**
 * 对话视频转写实现（B11，方案 A 后台任务型）：
 * 提交时创建占位消息并投入 courseExecutor；任务执行「抽音频 → 280s 分片 → Qwen ASR → 时间戳偏移合并」，
 * 每片完成后更新占位消息 payload 进度并推送 TRANSCRIBE 事件（前端渲染「正在转写第 x / y 段」）。
 * 全部完成后占位消息原地更新为转写全文（[mm:ss] 句级格式），同时追加进 Redis 会话记忆，
 * 保证后续回合 LLM 能基于转写内容回答。失败时占位消息更新为失败文案，原片已上传 OSS 保留。
 */
@Slf4j
@Service
public class TranscriptionServiceImpl implements TranscriptionService {

    /** ASR 单次转写的音频时长上限（服务限制 300s，留余量），与网课流水线一致 */
    private static final int ASR_CHUNK_SEC = 280;

    @Resource
    private MessageMapper messageMapper;

    @Resource
    private AliUploadUtils aliUploadUtils;

    @Resource
    private QwenAsrTool qwenAsrTool;

    @Resource
    private AgentEventPushService pushService;

    @Resource
    private ChatMemoryRepository chatMemoryRepository;

    @Resource(name = "courseExecutor")
    private ThreadPoolExecutor courseExecutor;

    @Value("${xj.agent.memory.max-messages:100}")
    private int maxMessages;

    @Override
    public Long submit(Long userId, Long conversationId, String videoTempPath, int durationSec) {
        Path video = validateTempPath(videoTempPath);
        if (!Files.exists(video)) {
            throw new BusinessException("视频临时文件已丢失，请重新上传");
        }

        // 创建转写占位消息（事实源先落库，前端经 TRANSCRIBE 事件获知并渲染进度）
        Message placeholder = new Message()
                .setConversationId(conversationId)
                .setRole("assistant")
                .setMsgType("video_transcript")
                .setContent("")
                .setPayload(transcribePayload("processing", 0, estimateChunks(durationSec), null, null).toString())
                .setCreatedAt(LocalDateTime.now());
        messageMapper.insert(placeholder);
        long messageId = placeholder.getId();

        courseExecutor.execute(() -> runTask(userId, conversationId, messageId, video, durationSec));
        log.info("视频转写任务已提交, userId={}, conversationId={}, messageId={}, durationSec={}",
                userId, conversationId, messageId, durationSec);
        return messageId;
    }

    /** 任务主体：提交给线程池执行；测试直接调用本方法（不经过线程池） */
    void runTask(Long userId, Long conversationId, Long messageId, Path video, int durationSec) {
        try {
            String transcript = doTranscribe(userId, conversationId, messageId, video, durationSec);
            finishMessage(userId, messageId, "done", transcript, null);

            // 转写全文追加进 Redis 会话记忆：后续回合 LLM 可直接基于内容回答（滑窗裁剪与记忆 Advisor 一致）
            appendToMemory(conversationId, transcript);
            log.info("视频转写完成, userId={}, messageId={}, durationSec={}", userId, messageId, durationSec);
        } catch (Exception e) {
            log.error("视频转写失败, userId={}, messageId={}", userId, messageId, e);
            finishMessage(userId, messageId, "failed", null, e.getMessage());
        } finally {
            cleanupQuietly(video);
        }
    }

    /** 抽音频 → 分片转写（含时间戳偏移合并与进度推送），返回 [mm:ss] 句级行文本 */
    private String doTranscribe(Long userId, Long conversationId, Long messageId, Path video, int durationSec) throws Exception {
        int total = estimateChunks(durationSec);
        pushProgress(userId, messageId, "processing", 0, total, null);

        // 原片上传 OSS 永久保留（方案 A：重试 / 日后补 LLM 整理 / 抽帧都无需重传），并回填用户消息 video_url
        String videoUrl = aliUploadUtils.uploadLocalFile(video,
                videoKey(userId, messageId, "video." + extOf(video.getFileName().toString())));
        markUserMessageVideo(conversationId, videoUrl);

        Path audio = Files.createTempFile("xj-audio-", ".wav");
        try {
            MediaUtils.extractAudio(video, audio);

            List<AsrSegment> segments = new ArrayList<>();
            if (durationSec <= ASR_CHUNK_SEC) {
                // 短音频：整段上传一次转写
                String audioUrl = aliUploadUtils.uploadLocalFile(audio, audioKey(userId, messageId, "audio.wav"));
                segments.addAll(qwenAsrTool.transcribeSegments(audioUrl));
                pushProgress(userId, messageId, "processing", 1, total, null);
            } else {
                int done = 0;
                for (int start = 0; start < durationSec; start += ASR_CHUNK_SEC) {
                    int len = Math.min(ASR_CHUNK_SEC, durationSec - start);
                    Path chunk = Files.createTempFile("xj-audio-chunk-", ".wav");
                    try {
                        MediaUtils.run("ffmpeg", "-y", "-ss", String.valueOf(start), "-t", String.valueOf(len),
                                "-i", audio.toString(), "-c", "copy", chunk.toString());
                        String chunkUrl = aliUploadUtils.uploadLocalFile(chunk,
                                audioKey(userId, messageId, "audio-" + start + ".wav"));
                        int offsetMs = start * 1000;
                        for (AsrSegment segment : qwenAsrTool.transcribeSegments(chunkUrl)) {
                            segments.add(new AsrSegment(offsetMs + segment.getBeginMs(),
                                    offsetMs + segment.getEndMs(), segment.getText()));
                        }
                    } finally {
                        cleanupQuietly(chunk);
                    }
                    done++;
                    pushProgress(userId, messageId, "processing", done, total, null);
                }
            }
            return assembleTranscript(segments, durationSec);
        } finally {
            cleanupQuietly(audio);
        }
    }

    /** 句级分段组装为 [mm:ss] 行文本（与网课转写展示格式一致） */
    String assembleTranscript(List<AsrSegment> segments, int durationSec) {
        if (segments.isEmpty()) {
            throw new IllegalStateException("未识别出音频中的语音内容");
        }
        int chars = 0;
        StringBuilder text = new StringBuilder();
        for (AsrSegment segment : segments) {
            text.append('[').append(formatTs(segment.getBeginMs() / 1000)).append("] ")
                    .append(segment.getText()).append('\n');
            chars += segment.getText().length();
        }
        String head = "**视频转写完成**（时长 " + formatTs(durationSec) + "，共 " + segments.size()
                + " 段，约 " + chars + " 字）\n\n"
                + "你可以让我总结要点，或回复「保存」把它收进笔记管理（分组与标题由我来起）。\n\n";
        return head + text.toString().trim();
    }

    /** 占位消息原地更新（内容 / payload 状态）并推送事件 */
    private void finishMessage(Long userId, Long messageId, String status, String content, String errorMessage) {
        Message message = messageMapper.selectById(messageId);
        if (message == null) {
            return;
        }
        JSONObject payload;
        try {
            payload = JSONUtil.parseObj(message.getPayload() == null ? "{}" : message.getPayload());
        } catch (Exception e) {
            payload = new JSONObject();
        }
        payload.set("status", status);
        if ("failed".equals(status)) {
            payload.set("error", errorMessage == null ? "" : errorMessage);
            message.setContent("视频转写失败（" + (errorMessage == null ? "服务异常" : errorMessage)
                    + "）。视频已保留，可重新上传后再试。");
        } else {
            message.setContent(content == null ? "" : content);
        }
        message.setPayload(payload.toString());
        messageMapper.updateById(message);
        pushService.pushToUser(userId, ChatEvent.transcribe(messageId, status,
                "done".equals(status) ? content : null, null, null, errorMessage));
    }

    /** 进度推送（不改消息内容，只更新 payload 进度字段） */
    private void pushProgress(Long userId, Long messageId, String status, int done, int total, String error) {
        Message message = messageMapper.selectById(messageId);
        if (message == null) {
            return;
        }
        JSONObject payload;
        try {
            payload = JSONUtil.parseObj(message.getPayload() == null ? "{}" : message.getPayload());
        } catch (Exception e) {
            payload = new JSONObject();
        }
        payload.set("status", status);
        payload.set("done", done);
        payload.set("total", total);
        message.setPayload(payload.toString());
        messageMapper.updateById(message);
        pushService.pushToUser(userId, ChatEvent.transcribe(messageId, status, null, done, total, error));
    }

    private void appendToMemory(Long conversationId, String transcript) {
        try {
            String conversationKey = String.valueOf(conversationId);
            List<org.springframework.ai.chat.messages.Message> memory =
                    new ArrayList<>(chatMemoryRepository.findByConversationId(conversationKey));
            memory.add(new AssistantMessage(transcript));
            if (memory.size() > maxMessages) {
                memory = new ArrayList<>(memory.subList(memory.size() - maxMessages, memory.size()));
            }
            chatMemoryRepository.saveAll(conversationKey, memory);
        } catch (Exception e) {
            // 记忆追加失败不阻塞结果回流：下次 Redis 缺失时会从 message 表重建补齐
            log.warn("转写内容追加会话记忆失败, conversationId={}", conversationId, e);
        }
    }

    /** 校验临时文件路径必须位于系统临时目录内（客户端回传，防路径穿越） */
    private Path validateTempPath(String videoTempPath) {
        if (videoTempPath == null || videoTempPath.isBlank()) {
            throw new BusinessException("缺少视频文件");
        }
        Path tmpRoot = Path.of(System.getProperty("java.io.tmpdir")).toAbsolutePath().normalize();
        Path video = Path.of(videoTempPath).toAbsolutePath().normalize();
        if (!video.startsWith(tmpRoot)) {
            throw new BusinessException("非法的视频文件路径");
        }
        return video;
    }

    private int estimateChunks(int durationSec) {
        return Math.max(1, (durationSec + ASR_CHUNK_SEC - 1) / ASR_CHUNK_SEC);
    }

    /** 原片上传完成后回填用户消息 video_url（历史渲染与日后补整理的索引） */
    private void markUserMessageVideo(Long conversationId, String videoUrl) {
        Message userMessage = messageMapper.selectOne(new com.baomidou.mybatisplus.core.conditions.query.QueryWrapper<Message>()
                .eq("conversation_id", conversationId)
                .eq("role", "user")
                .eq("msg_type", "video")
                .orderByDesc("id")
                .last("LIMIT 1"));
        if (userMessage == null) {
            return;
        }
        JSONObject payload;
        try {
            payload = JSONUtil.parseObj(userMessage.getPayload() == null ? "{}" : userMessage.getPayload());
        } catch (Exception e) {
            payload = new JSONObject();
        }
        payload.set("videoUrl", videoUrl);
        userMessage.setVideoUrl(videoUrl).setPayload(payload.toString());
        messageMapper.updateById(userMessage);
    }

    private String videoKey(Long userId, Long messageId, String fileName) {
        return "chat-transcribe/" + userId + "/" + messageId + "/" + fileName;
    }

    private String extOf(String fileName) {
        int dot = fileName.lastIndexOf('.');
        return dot < 0 ? "mp4" : fileName.substring(dot + 1).toLowerCase();
    }

    private String audioKey(Long userId, Long messageId, String fileName) {
        return "chat-transcribe/" + userId + "/" + messageId + "/" + fileName;
    }

    private JSONObject transcribePayload(String status, int done, int total, String content, String error) {
        JSONObject payload = new JSONObject()
                .set("status", status)
                .set("done", done)
                .set("total", total);
        if (content != null) {
            payload.set("content", content);
        }
        if (error != null) {
            payload.set("error", error);
        }
        return payload;
    }

    private String formatTs(int sec) {
        int h = sec / 3600;
        int m = (sec % 3600) / 60;
        int s = sec % 60;
        String mm = String.format("%02d", m);
        String ss = String.format("%02d", s);
        return h > 0 ? h + ":" + mm + ":" + ss : mm + ":" + ss;
    }

    private void cleanupQuietly(Path file) {
        if (file != null) {
            try {
                Files.deleteIfExists(file);
            } catch (IOException ignored) {
            }
        }
    }
}
