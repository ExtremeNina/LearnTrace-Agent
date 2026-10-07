package com.xueji.agent.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.xueji.agent.ai.CorrectionCandidate;
import com.xueji.agent.ai.NotePipelineGraphRunner;
import com.xueji.agent.ai.RagIngestService;
import com.xueji.agent.ai.TranscriptReviewService;
import com.xueji.agent.ai.tool.AsrSegment;
import com.xueji.agent.ai.NoteGenerationService;
import com.xueji.agent.ai.tool.OcrTool;
import com.xueji.agent.ai.tool.QwenAsrTool;
import com.xueji.agent.domain.enums.CourseStatus;
import com.xueji.agent.domain.entity.Course;
import com.xueji.agent.domain.entity.CourseFrame;
import com.xueji.agent.domain.entity.CourseTranscriptSegment;
import com.xueji.agent.domain.entity.Message;
import com.xueji.agent.domain.vo.ChatEvent;
import com.xueji.agent.mapper.CourseFrameMapper;
import com.xueji.agent.mapper.CourseMapper;
import com.xueji.agent.mapper.CourseTranscriptSegmentMapper;
import com.xueji.agent.mapper.MessageMapper;
import com.xueji.agent.utils.AliUploadUtils;
import com.xueji.agent.utils.MediaUtils;
import com.xueji.agent.ws.AgentEventPushService;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 网课处理流水线（PRD §3.2，MQ 消费者调用）：
 * 视频上传 OSS → FFmpeg 抽音频/抽帧（场景检测+去重）→ 语音转写 ‖ 关键帧批量 OCR（线程池并发）
 * → 结果落库（transcript_segment + course_frame）。
 * 音频与画面两条通道互不依赖：单通道失败不阻塞整体，两通道均失败才置 FAILED。
 * LLM 笔记生成与确认卡片为下一阶段（确认机制未建，暂不自动入库笔记）。
 */
@Slf4j
@Service
public class CoursePipelineService {

    private static final int MAX_FRAMES = 60;
    private static final int MIN_FRAME_INTERVAL_SEC = 5;
    private static final Pattern PTS_TIME = Pattern.compile("pts_time:([0-9.]+)");
    /** ASR 单次转写的音频时长上限（服务限制 300s，留余量） */
    private static final int ASR_CHUNK_SEC = 280;

    @Resource
    private CourseMapper courseMapper;

    @Resource
    private CourseTranscriptSegmentMapper transcriptMapper;

    @Resource
    private CourseFrameMapper frameMapper;

    @Resource
    private NotePipelineGraphRunner notePipelineGraphRunner;

    @Resource
    private com.xueji.agent.service.CourseQuizService courseQuizService;

    @Resource
    private AliUploadUtils aliUploadUtils;

    @Resource
    private QwenAsrTool qwenAsrTool;

    @Resource
    private OcrTool ocrTool;

    @Resource
    private NoteGenerationService noteGenerationService;

    @Resource
    private RagIngestService ragIngestService;

    @Resource
    private TranscriptReviewService transcriptReviewService;

    @Resource
    private com.xueji.agent.service.TranscriptCorrectionService transcriptCorrectionService;

    @Resource
    private MessageMapper messageMapper;

    @Resource
    private AgentEventPushService pushService;

    @Resource(name = "courseExecutor")
    private ThreadPoolExecutor courseExecutor;

    /**
     * 执行完整流水线。任何未捕获异常由调用方记录（课程已置为 FAILED）
     */
    public void process(Long courseId, String tempPath) {
        Course course = courseMapper.selectById(courseId);
        if (course == null) {
            log.warn("流水线跳过：课程不存在, courseId={}", courseId);
            return;
        }
        if (CourseStatus.SUCCESS.equals(course.getStatus())) {
            return;
        }
        updateStatus(course, CourseStatus.PROCESSING, null);

        // 对话创建的课程任务占位消息（页面路径上传为 NULL，跳过推送）
        Message taskMessage = messageMapper.selectOne(new QueryWrapper<Message>()
                .eq("msg_type", "course_task")
                .eq("course_id", courseId)
                .orderByDesc("id")
                .last("LIMIT 1"));

        Path video = Path.of(tempPath == null ? "" : tempPath);
        if (!Files.exists(video)) {
            markFailed(course, "本地上传文件已丢失，请删除后重新上传该网课", taskMessage);
            return;
        }

        try {
            // 0. 视频上传 OSS
            pushCourseStage(course, taskMessage, "UPLOADING", "正在上传视频到云存储…", null);
            String videoUrl = aliUploadUtils.uploadLocalFile(video, "course/" + courseId + "/video."
                    + extOf(video.getFileName().toString()));
            course.setVideoOssKey(videoUrl).setVideoSize(Files.size(video));

            // 1. 时长
            int durationSec = MediaUtils.ffprobeDurationSec(video);

            // 2. FFmpeg：抽音频 + 抽关键帧（场景检测，不足时回退定间隔）
            pushCourseStage(course, taskMessage, "EXTRACTING", "正在提取音频与关键帧…", null);
            Path audio = Files.createTempFile("xj-audio-", ".wav");
            MediaUtils.extractAudio(video, audio);
            List<Integer> frameSecs = new ArrayList<>();
            List<Path> frameFiles = extractFrames(video, frameSecs, durationSec);

            // 3. 音频通道：上传音频 → Qwen ASR 转写（句级分段；ASR 单次上限 300s，长音频按 280s 分片）
            pushCourseStage(course, taskMessage, "TRANSCRIBING", "正在转写语音…", null);
            List<AsrSegment> asrSegments = new ArrayList<>();
            String transcriptError = null;
            try {
                String audioUrl = aliUploadUtils.uploadLocalFile(audio, "course/" + courseId + "/audio.wav");
                if (durationSec <= ASR_CHUNK_SEC) {
                    asrSegments.addAll(qwenAsrTool.transcribeSegments(audioUrl));
                } else {
                    for (int start = 0; start < durationSec; start += ASR_CHUNK_SEC) {
                        int len = Math.min(ASR_CHUNK_SEC, durationSec - start);
                        Path chunk = Files.createTempFile("xj-audio-chunk-", ".wav");
                        MediaUtils.run("ffmpeg", "-y", "-ss", String.valueOf(start), "-t", String.valueOf(len),
                                "-i", audio.toString(), "-c", "copy", chunk.toString());
                        String chunkUrl = aliUploadUtils.uploadLocalFile(chunk,
                                "course/" + courseId + "/audio-" + start + ".wav");
                        int offsetMs = start * 1000;
                        for (AsrSegment segment : qwenAsrTool.transcribeSegments(chunkUrl)) {
                            asrSegments.add(new AsrSegment(offsetMs + segment.getBeginMs(),
                                    offsetMs + segment.getEndMs(), segment.getText()));
                        }
                        Files.deleteIfExists(chunk);
                    }
                }
            } catch (Exception e) {
                transcriptError = e.getMessage();
                log.warn("音频转写失败（降级为仅画面通道）, courseId={}", courseId, e);
            }

            // 4. 画面通道：帧图上传 OSS → 批量 OCR（线程池并发，单帧失败不影响其他帧）
            pushCourseStage(course, taskMessage, "ANALYZING", "正在识别画面关键帧…", null);
            List<Future<CourseFrame>> futures = new ArrayList<>();
            for (int i = 0; i < frameFiles.size(); i++) {
                Path frameFile = frameFiles.get(i);
                int sec = frameSecs.get(i);
                futures.add(courseExecutor.submit(() -> {
                    CourseFrame frame = new CourseFrame()
                            .setCourseId(courseId)
                            .setTimeSec(sec)
                            .setOcrStatus(CourseStatus.FAILED);
                    try {
                        String frameUrl = aliUploadUtils.uploadLocalFile(frameFile,
                                "course/" + courseId + "/frames/" + sec + ".jpg");
                        frame.setOssKey(frameUrl);
                        frame.setOcrText(ocrTool.recognizeText(frameUrl));
                        frame.setOcrStatus(CourseStatus.SUCCESS);
                    } catch (Exception e) {
                        log.warn("帧 OCR 失败, courseId={}, sec={}", courseId, sec, e);
                        frame.setOcrText(null);
                    }
                    return frame;
                }));
            }
            List<CourseFrame> frames = new ArrayList<>();
            for (Future<CourseFrame> future : futures) {
                frames.add(future.get());
            }

            // 5. 落库：转写（句级分段，带时间戳）+ 关键帧
            transcriptMapper.delete(new QueryWrapper<CourseTranscriptSegment>().eq("course_id", courseId));
            frameMapper.delete(new QueryWrapper<CourseFrame>().eq("course_id", courseId));
            int transcriptChars = 0;
            List<CourseTranscriptSegment> transcriptRows = new ArrayList<>();
            if (asrSegments != null) {
                int sort = 0;
                for (AsrSegment segment : asrSegments) {
                    CourseTranscriptSegment row = new CourseTranscriptSegment()
                            .setCourseId(courseId)
                            .setStartSec(segment.getBeginMs() / 1000)
                            .setEndSec(segment.getEndMs() / 1000)
                            .setText(segment.getText())
                            .setSort(sort++);
                    transcriptMapper.insert(row);
                    transcriptRows.add(row);
                    transcriptChars += segment.getText().length();
                }
            }
            for (CourseFrame frame : frames) {
                if (frame.getOssKey() != null) {
                    frameMapper.insert(frame);
                }
            }

            // 5.5 转写修正管线（B26 阶段 1）：LLM 候选 + 帧 OCR 交叉确认（失败静默，保留原始转写继续）
            try {
                List<CorrectionCandidate> candidates = transcriptReviewService.review(transcriptRows);
                if (!candidates.isEmpty()) {
                    transcriptCorrectionService.apply(course, transcriptRows, candidates, frames);
                }
            } catch (Exception e) {
                log.warn("转写修正管线执行失败（保留原始转写继续）, courseId={}", courseId, e);
            }

            // 6. 收尾状态
            boolean audioOk = asrSegments != null && !asrSegments.isEmpty();
            boolean framesOk = false;
            for (CourseFrame frame : frames) {
                if (CourseStatus.SUCCESS.equals(frame.getOcrStatus())) {
                    framesOk = true;
                    break;
                }
            }
            if (!audioOk && !framesOk) {
                markFailed(course, "转写与画面识别均失败"
                        + (transcriptError != null ? "（转写：" + transcriptError + "）" : ""), taskMessage);
                return;
            }

            // 7. 内容理解 + 笔记渲染（B26 阶段 2：ContentDocument → Renderer，失败回退旧链路）
            String noteIssue = understandAndRenderNote(course, taskMessage, frames, durationSec);

            // 8. 课后习题（B26 习题产物化：出题 Agent + 判题 Agent 三明治质检）；失败不阻断课程完成
            String quizIssue = null;
            try {
                pushCourseStage(course, taskMessage, "QUIZ_GENERATING", "正在生成课后习题…", null);
                List<CourseTranscriptSegment> transcriptForQuiz = transcriptMapper.selectList(
                        new QueryWrapper<CourseTranscriptSegment>().eq("course_id", courseId).orderByAsc("sort"));
                courseQuizService.regenerateForCourse(course, transcriptForQuiz);
            } catch (Exception e) {
                quizIssue = "课后习题生成失败：" + truncate(e.getMessage());
                log.warn("课后习题生成失败（课程处理仍为成功）, courseId={}", courseId, e);
            }
            String finalIssue = noteIssue == null ? quizIssue
                    : quizIssue == null ? noteIssue : noteIssue + "；" + quizIssue;

            course.setStatus(CourseStatus.SUCCESS)
                    .setDuration(durationSec)
                    .setErrorMsg(finalIssue == null ? null : "网课处理完成，但 " + finalIssue)
                    .setUpdatedAt(LocalDateTime.now());
            courseMapper.updateById(course);
            pushCourseStage(course, taskMessage, "COMPLETED",
                    "课程《" + course.getTitle() + "》处理完成" + (noteIssue == null ? "，AI 笔记已生成" : "，但 AI 笔记待改进"),
                    "/courses/" + courseId);
            // 转写分段参与 RAG 检索（异步，失败不影响课程状态）
            ragIngestService.ingestCourseTranscriptsAsync(course, transcriptMapper.selectList(
                    new QueryWrapper<CourseTranscriptSegment>()
                            .eq("course_id", courseId)
                            .orderByAsc("sort")));
            log.info("网课流水线完成, courseId={}, 时长={}s, 帧数={}, 转写句数={}, 转写字数={}, 笔记待改进={}",
                    courseId, durationSec, frames.size(), asrSegments == null ? 0 : asrSegments.size(),
                    transcriptChars, noteIssue != null);
        } catch (Exception e) {
            log.error("网课流水线异常, courseId={}", courseId, e);
            markFailed(course, "处理异常：" + truncate(e.getMessage()), taskMessage);
        } finally {
            cleanupQuietly(video);
        }
    }

    // ---- 内容理解 + 评审 + 笔记渲染（B26 阶段 3：SAA Graph 子流程，节点函数化） ----

    /**
     * 理解 - 评审 - 渲染子流程（NotePipelineGraphRunner，SAA StateGraph 驱动）：
     * L1 内容设计评审前置门禁 → L2 讲解 / 练习双评审并行。
     * Graph 整体失败时回退旧链路（从转写直接生成），保证「上传完成即有笔记」不被语义层故障拖垮。
     *
     * @return 待写入 course.errorMsg 的问题描述（NULL = 全部通过）
     */
    private String understandAndRenderNote(Course course, Message taskMessage,
                                           List<CourseFrame> frames, int durationSec) {
        List<CourseTranscriptSegment> transcriptRows = transcriptMapper.selectList(
                new QueryWrapper<CourseTranscriptSegment>().eq("course_id", course.getId()).orderByAsc("sort"));
        try {
            NotePipelineGraphRunner.RunResult result = notePipelineGraphRunner.run(course, transcriptRows, frames,
                    durationSec,
                    (stage, text) -> pushCourseStage(course, taskMessage, stage, text, null));
            return result.getNoteIssue();
        } catch (Exception e) {
            log.warn("Graph 子流程失败，回退旧链路从转写直接生成, courseId={}", course.getId(), e);
        }

        // 回退：旧链路从转写直接生成（保底，保证「上传完成即有笔记」）
        try {
            pushCourseStage(course, taskMessage, "NOTE_GENERATING", "正在生成 AI 笔记…", null);
            NoteGenerationService.NoteGenerationResult result = noteGenerationService.generateAndSaveNote(
                    course, transcriptRows, frames, durationSec);
            if (!result.getQualityDefects().isEmpty()) {
                return "AI 笔记质检未通过（" + String.join("；", result.getQualityDefects()) + "），已降级入库";
            }
            return null;
        } catch (Exception e) {
            log.warn("AI 笔记生成失败（课程处理仍为成功）, courseId={}", course.getId(), e);
            return "AI 笔记生成失败：" + truncate(e.getMessage());
        }
    }

    // ---- FFmpeg ----

    /**
     * 抽关键帧：优先场景切换（含第 0 帧），不足时回退为按帧号采样（每 60s 一帧），
     * 仍无帧则保底抽取第 0 帧；相邻帧间隔小于 5s 的去重；总数上限 60
     */
    List<Path> extractFrames(Path video, List<Integer> frameSecs, int durationSec) throws IOException, InterruptedException {
        Path dir = Files.createTempDirectory("xj-frames-");
        List<Path> files = new ArrayList<>();
        boolean scene = tryExtractFrames(video, dir,
                "select='eq(n,0)+gt(scene,0.3)',showinfo", MAX_FRAMES, files, frameSecs);
        if (!scene || files.size() < 2) {
            files.clear();
            frameSecs.clear();
            // 每约 60s 一帧（按 25fps 折算 1500 帧）；低帧率视频至少能命中第 0 帧
            tryExtractFrames(video, dir, "select='eq(n,0)+not(mod(n,1500))',showinfo", 30, files, frameSecs);
        }
        if (files.isEmpty()) {
            // 保底：第 0 帧
            Path first = dir.resolve("frame_000.jpg");
            MediaUtils.run("ffmpeg", "-y", "-i", video.toString(), "-frames:v", "1", first.toString());
            if (Files.exists(first)) {
                files.add(first);
                frameSecs.add(0);
            }
        }
        // 去重：相邻帧间隔 < 5s 丢弃后面的
        List<Path> kept = new ArrayList<>();
        List<Integer> keptSecs = new ArrayList<>();
        int lastSec = -100;
        for (int i = 0; i < files.size(); i++) {
            int sec = frameSecs.get(i);
            if (sec - lastSec >= MIN_FRAME_INTERVAL_SEC || lastSec < 0) {
                kept.add(files.get(i));
                keptSecs.add(sec);
                lastSec = sec;
            }
        }
        frameSecs.clear();
        frameSecs.addAll(keptSecs);
        log.info("抽帧完成, 原始={} 去重后={}", files.size(), kept.size());
        return kept;
    }

    private boolean tryExtractFrames(Path video, Path dir, String vf, int maxFrames,
                                     List<Path> files, List<Integer> frameSecs) throws IOException, InterruptedException {
        Path outPattern = dir.resolve("frame_%03d.jpg");
        // 新版 FFmpeg 已移除 -vsync，统一使用 -fps_mode vfr
        Process p = new ProcessBuilder("ffmpeg", "-y", "-i", video.toString(),
                "-vf", vf, "-frames:v", String.valueOf(maxFrames), "-fps_mode", "vfr",
                outPattern.toString()).redirectErrorStream(true).start();
        String logText = new String(p.getInputStream().readAllBytes());
        p.waitFor();


        Matcher matcher = PTS_TIME.matcher(logText);
        List<Double> pts = new ArrayList<>();
        while (matcher.find()) {
            pts.add(Double.parseDouble(matcher.group(1)));
        }
        // 显式遍历目录收集帧文件（glob 只取 .jpg），按文件名自然序排列（frame_%03d.jpg 零填充，字典序即帧序）
        try (DirectoryStream<Path> dirStream = Files.newDirectoryStream(dir, "*.jpg")) {
            for (Path frameFile : dirStream) {
                files.add(frameFile);
            }
        }
        Collections.sort(files);
        if (files.isEmpty() || pts.isEmpty()) {
            return false;
        }
        for (int i = 0; i < files.size() && i < pts.size(); i++) {
            frameSecs.add((int) Math.round(pts.get(i)));
        }
        return true;
    }

    // ---- 工具 ----

    private void updateStatus(Course course, String status, String errorMsg) {
        course.setStatus(status).setErrorMsg(errorMsg).setUpdatedAt(LocalDateTime.now());
        courseMapper.updateById(course);
    }

    private void markFailed(Course course, String message, Message taskMessage) {
        log.error("网课处理失败, courseId={}: {}", course.getId(), message);
        updateStatus(course, CourseStatus.FAILED, truncate(message));
        pushCourseStage(course, taskMessage, "FAILED", "课程处理失败：" + truncate(message), null);
    }

    /**
     * 课程任务进度回流（B11 分流）：更新占位消息 payload / 完成内容并推送 COURSE 事件。
     * taskMessage 为空（页面路径上传）时静默跳过
     */
    private void pushCourseStage(Course course, Message taskMessage, String stage, String text, String extra) {
        try {
            // 阶段持久化（B26 阶段 1）：course.stage 记录细分进度，列表页展示（无占位消息也持久化）
            course.setStage(stage);
            courseMapper.updateById(course);
        } catch (Exception e) {
            log.warn("课程阶段持久化失败, courseId={}, stage={}", course.getId(), stage, e);
        }
        if (taskMessage == null) {
            return;
        }
        try {
            cn.hutool.json.JSONObject payload;
            try {
                payload = cn.hutool.json.JSONUtil.parseObj(taskMessage.getPayload() == null ? "{}" : taskMessage.getPayload());
            } catch (Exception e) {
                payload = new cn.hutool.json.JSONObject();
            }
            boolean done = "COMPLETED".equals(stage);
            boolean failed = "FAILED".equals(stage);
            payload.set("status", done ? "done" : failed ? "failed" : "processing");
            payload.set("stage", stage);
            if (extra != null) {
                payload.set("link", extra);
            }
            taskMessage.setPayload(payload.toString());
            if (done) {
                taskMessage.setContent(text + "。点击链接查看课程与 AI 笔记：" + extra);
            } else if (failed) {
                taskMessage.setContent(text);
            }
            messageMapper.updateById(taskMessage);
            pushService.pushToUser(course.getUserId(), ChatEvent.course(taskMessage.getId(), stage, text, extra));
        } catch (Exception e) {
            // 进度推送失败不影响流水线主流程
            log.warn("课程任务进度推送失败, courseId={}, stage={}", course.getId(), stage, e);
        }
    }

    private String truncate(String s) {
        return s == null ? "" : s.substring(0, Math.min(500, s.length()));
    }

    private String extOf(String fileName) {
        int dot = fileName.lastIndexOf('.');
        return dot < 0 ? "mp4" : fileName.substring(dot + 1).toLowerCase();
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
