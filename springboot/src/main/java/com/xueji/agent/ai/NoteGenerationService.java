package com.xueji.agent.ai;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.xueji.agent.ai.prompt.AgentPrompts;
import com.xueji.agent.config.ChatClientFactory;
import com.xueji.agent.ai.tool.AsrSegment;
import com.xueji.agent.domain.enums.CourseStatus;
import com.xueji.agent.domain.entity.ContentDocument;
import com.xueji.agent.domain.entity.ContentKnowledgePoint;
import com.xueji.agent.domain.entity.ContentSection;
import com.xueji.agent.domain.entity.Course;
import com.xueji.agent.domain.entity.CourseFrame;
import com.xueji.agent.domain.entity.CourseTranscriptSegment;
import com.xueji.agent.domain.entity.Note;
import com.xueji.agent.mapper.NoteMapper;
import com.xueji.agent.service.NoteQualityChecker;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 网课笔记生成（流水线末端 LLM 阶段）：
 * 语音转写分段 + 关键帧识别文本 + 用户期望 → COURSE_TRANSCRIPT_PROMPT → Markdown 笔记草稿 → 质量校验 → note 表入库。
 * 质检未通过时带缺陷清单重生成一次（B26 阶段 1），仍不合格降级入库并由调用方标记。
 * 生成的笔记直接入库（source_type = 网课 AI 生成）；确认卡片方针确定后再补确认环节。
 */
@Slf4j
@Service
public class NoteGenerationService {

    /** 质检最多重生成次数（首次生成 + N 次带反馈重生成） */
    private static final int MAX_QUALITY_RETRY = 1;

    @Resource
    private com.xueji.agent.service.AiModelService aiModelService;

    @Resource
    private NoteMapper noteMapper;

    @Resource
    private RagIngestService ragIngestService;

    /** 生成结果：笔记 ID + 笔记全文 + 质检缺陷（空 = 质检通过） */
    public static class NoteGenerationResult {
        private final Long noteId;
        private final String content;
        private final List<String> qualityDefects;

        public NoteGenerationResult(Long noteId, List<String> qualityDefects) {
            this(noteId, null, qualityDefects);
        }

        public NoteGenerationResult(Long noteId, String content, List<String> qualityDefects) {
            this.noteId = noteId;
            this.content = content;
            this.qualityDefects = qualityDefects;
        }

        public Long getNoteId() {
            return noteId;
        }

        public String getContent() {
            return content;
        }

        public List<String> getQualityDefects() {
            return qualityDefects;
        }
    }

    /**
     * 生成笔记并入库（重试幂等：先删除该课程此前生成的 AI 笔记再插入）。
     * 生成后经 NoteQualityChecker 校验，不合格带缺陷清单重生成一次（B26 阶段 1）。
     * 生成失败抛出异常，由调用方决定降级方式
     */
    public NoteGenerationResult generateAndSaveNote(Course course, List<CourseTranscriptSegment> transcript,
                                                    List<CourseFrame> frames, int durationSec) {
        String markdown = normalizeTimestamps(generate(course, transcript, frames, durationSec, List.of()));
        List<String> defects = NoteQualityChecker.check(markdown, transcript, durationSec);
        if (!defects.isEmpty()) {
            log.warn("AI 笔记质检未通过，带缺陷重生成一次, courseId={}, defects={}", course.getId(), defects);
            markdown = normalizeTimestamps(generate(course, transcript, frames, durationSec, defects));
            defects = NoteQualityChecker.check(markdown, transcript, durationSec);
            if (!defects.isEmpty()) {
                log.warn("AI 笔记重生成后质检仍未通过，降级入库, courseId={}, defects={}", course.getId(), defects);
            }
        }
        return saveNote(course, markdown, defects);
    }

    /**
     * 从 ContentDocument 渲染笔记（B26 阶段 2 Renderer 起步：Markdown 一个）。
     * 输入 = 内容文档（章节/知识点）+ 转写原文（补充细节与时间戳校准）+ 评审反馈（阶段 3 L2 定向重生成），
     * 输出保持四段结构以兼容质检与前端
     */
    public NoteGenerationResult generateAndSaveNoteFromDocument(Course course, ContentDocument document,
                                                                List<ContentSection> sections,
                                                                List<ContentKnowledgePoint> knowledgePoints,
                                                                List<CourseTranscriptSegment> transcript,
                                                                List<CourseFrame> frames, int durationSec,
                                                                List<String> reviewFeedback) {
        String markdown = normalizeTimestamps(generateFromDocument(course, document, sections, knowledgePoints,
                transcript, frames, durationSec, reviewFeedback));
        List<String> defects = NoteQualityChecker.check(markdown, transcript, durationSec);
        if (!defects.isEmpty()) {
            log.warn("AI 笔记质检未通过，带缺陷重生成一次, courseId={}, defects={}", course.getId(), defects);
            markdown = normalizeTimestamps(generateFromDocument(course, document, sections, knowledgePoints,
                    transcript, frames, durationSec, defects));
            defects = NoteQualityChecker.check(markdown, transcript, durationSec);
            if (!defects.isEmpty()) {
                log.warn("AI 笔记重生成后质检仍未通过，降级入库, courseId={}, defects={}", course.getId(), defects);
            }
        }
        return saveNote(course, markdown, defects);
    }

    /** 幂等落库：清掉旧 AI 笔记（同步移出向量库防孤儿向量）再插入新笔记并参与 RAG 检索 */
    private NoteGenerationResult saveNote(Course course, String markdown, List<String> defects) {
        List<Note> oldNotes = noteMapper.selectList(new QueryWrapper<Note>()
                .eq("course_id", course.getId())
                .eq("source_type", 1));
        noteMapper.delete(new QueryWrapper<Note>()
                .eq("course_id", course.getId())
                .eq("source_type", 1));
        for (Note oldNote : oldNotes) {
            ragIngestService.removeNote(oldNote.getId());
        }

        Note note = new Note()
                .setUserId(course.getUserId())
                .setTitle(course.getTitle() + " · AI 笔记")
                .setContent(markdown)
                .setNoteType(0)
                .setSourceType(1)
                .setCourseId(course.getId())
                .setCreatedAt(LocalDateTime.now())
                .setUpdatedAt(LocalDateTime.now());
        noteMapper.insert(note);
        // AI 笔记参与 RAG 检索
        ragIngestService.ingestNoteAsync(note);
        log.info("AI 笔记已生成入库, courseId={}, noteId={}, 字数={}, 质检缺陷={}",
                course.getId(), note.getId(), markdown.length(), defects.size());
        return new NoteGenerationResult(note.getId(), markdown, defects);
    }

    /**
     * 从 ContentDocument 调 LLM 渲染笔记 Markdown（qualityDefects 非空时为带质检反馈的重生成）
     */
    public String generateFromDocument(Course course, ContentDocument document,
                                       List<ContentSection> sections, List<ContentKnowledgePoint> knowledgePoints,
                                       List<CourseTranscriptSegment> transcript,
                                       List<CourseFrame> frames, int durationSec, List<String> qualityDefects) {
        String userContent = buildDocumentUserContent(document, sections, knowledgePoints,
                transcript, frames, durationSec, qualityDefects);
        ChatClient chatClient = aiModelService.resolveGenerationForCourse(course.getUserId(), course.getModelConfigId());
        String markdown = chatClient.prompt()
                .system(AgentPrompts.DOCUMENT_NOTE_PROMPT)
                .user(userContent)
                .call()
                .content();
        if (markdown == null || markdown.isBlank()) {
            throw new IllegalStateException("LLM 未返回笔记内容");
        }
        log.info("AI 笔记渲染完成（基于内容文档）, courseId={}, 字数={}", course.getId(), markdown.length());
        return markdown;
    }

    /**
     * 调 LLM 生成笔记 Markdown（qualityDefects 非空时为带质检反馈的重生成）
     */
    public String generate(Course course, List<CourseTranscriptSegment> transcript,
                           List<CourseFrame> frames, int durationSec, List<String> qualityDefects) {
        String userContent = buildUserContent(transcript, frames, course.getExpectations(), durationSec, qualityDefects);
        // 生成形态客户端（无工具无记忆）；模型按上传时选择的配置解析（课程上记录，缺失回退系统默认）
        ChatClient chatClient = aiModelService.resolveGenerationForCourse(course.getUserId(), course.getModelConfigId());
        String markdown = chatClient.prompt()
                .system(AgentPrompts.COURSE_TRANSCRIPT_PROMPT)
                .user(userContent)
                .call()
                .content();
        if (markdown == null || markdown.isBlank()) {
            throw new IllegalStateException("LLM 未返回笔记内容");
        }
        log.info("AI 笔记生成完成, courseId={}, 字数={}", course.getId(), markdown.length());
        return markdown;
    }

    /**
     * 组装用户消息：转写分段（带时间戳，优先修正版）+ 关键帧识别 + 用户期望 + 质检反馈。
     * 公开静态方法，便于单元测试
     */
    public static String buildUserContent(List<CourseTranscriptSegment> transcript,
                                          List<CourseFrame> frames, String expectations, int durationSec) {
        return buildUserContent(transcript, frames, expectations, durationSec, List.of());
    }

    public static String buildUserContent(List<CourseTranscriptSegment> transcript,
                                          List<CourseFrame> frames, String expectations, int durationSec,
                                          List<String> qualityDefects) {
        StringBuilder sb = new StringBuilder();
        sb.append("视频时长：").append(durationSec).append(" 秒。\n\n");
        sb.append(transcriptSection(transcript));
        sb.append(framesSection(frames));
        if (StringUtils.hasText(expectations)) {
            sb.append("\n[用户的特别要求]\n").append(expectations.trim()).append("\n");
            sb.append("生成笔记时优先满足以上要求；若要求与视频内容无关，请自然忽略，不要虚构内容。\n");
        }
        sb.append(qualityFeedback(qualityDefects));
        return sb.toString();
    }

    /**
     * 组装 Renderer 用户消息：内容文档（章节 + 知识点）+ 转写原文（补充细节与时间戳校准）+ 质检反馈。
     * 公开静态方法，便于单元测试
     */
    public static String buildDocumentUserContent(ContentDocument document,
                                                  List<ContentSection> sections,
                                                  List<ContentKnowledgePoint> knowledgePoints,
                                                  List<CourseTranscriptSegment> transcript,
                                                  List<CourseFrame> frames, int durationSec,
                                                  List<String> qualityDefects) {
        StringBuilder sb = new StringBuilder();
        sb.append("视频时长：").append(durationSec).append(" 秒。\n\n");

        sb.append("[内容文档 · 全局摘要]\n").append(document.getSummary() == null ? "" : document.getSummary()).append("\n\n");

        sb.append("[内容文档 · 章节]\n");
        for (ContentSection section : sections) {
            sb.append("- ").append(section.getTitle())
                    .append(" [").append(section.getStartSec()).append("-").append(section.getEndSec()).append("s]");
            if (section.getSummary() != null && !section.getSummary().isBlank()) {
                sb.append("：").append(section.getSummary());
            }
            sb.append('\n');
        }

        sb.append("\n[内容文档 · 知识点]\n");
        if (knowledgePoints == null || knowledgePoints.isEmpty()) {
            sb.append("（无）\n");
        } else {
            for (ContentKnowledgePoint point : knowledgePoints) {
                sb.append("- ").append(point.getName());
                if (point.getTimeSec() != null) {
                    sb.append(" [").append(point.getTimeSec()).append("s]");
                }
                if (point.getDetail() != null && !point.getDetail().isBlank()) {
                    sb.append("：").append(point.getDetail());
                }
                if (Integer.valueOf(1).equals(point.getImportant())) {
                    sb.append("（重点）");
                }
                if (Integer.valueOf(1).equals(point.getErrorProne())) {
                    sb.append("（易错点）");
                }
                sb.append('\n');
            }
        }

        sb.append('\n').append(transcriptSection(transcript));
        sb.append(framesSection(frames));
        sb.append(qualityFeedback(qualityDefects));
        return sb.toString();
    }

    /** 转写分段段（修正管线自动应用的文本优先，B26 阶段 1；原始转写仍随实体保留） */
    static String transcriptSection(List<CourseTranscriptSegment> transcript) {
        StringBuilder sb = new StringBuilder("[语音转写文本（句级分段，含起止秒）]\n");
        if (transcript == null || transcript.isEmpty()) {
            sb.append("（本视频未获得语音转写结果）\n");
            return sb.toString();
        }
        for (CourseTranscriptSegment segment : transcript) {
            String text = segment.getTextCorrected() != null ? segment.getTextCorrected() : segment.getText();
            sb.append("[").append(segment.getStartSec()).append("-").append(segment.getEndSec()).append("s] ")
                    .append(text).append("\n");
        }
        return sb.toString();
    }

    /** 关键帧画面识别段 */
    static String framesSection(List<CourseFrame> frames) {
        StringBuilder sb = new StringBuilder("\n[画面识别文本（关键帧，含时间戳）]\n");
        boolean hasFrames = false;
        if (frames != null) {
            for (CourseFrame frame : frames) {
                if (!CourseStatus.SUCCESS.equals(frame.getOcrStatus()) || !StringUtils.hasText(frame.getOcrText())) {
                    continue;
                }
                hasFrames = true;
                sb.append("[第 ").append(frame.getTimeSec()).append("s 画面]\n")
                        .append(frame.getOcrText()).append("\n\n");
            }
        }
        if (!hasFrames) {
            sb.append("（本视频未获得有效的画面识别结果）\n");
        }
        return sb.toString();
    }

    /** 时间戳归一化：秒数形式 [320s] / [320 秒] 与时分秒形式 [1:15:30]（B26 反馈 bug 修复） */
    private static final java.util.regex.Pattern SECONDS_TS =
            java.util.regex.Pattern.compile("\\[(\\d{1,5})\\s*(?:s|S|秒)\\]");
    private static final java.util.regex.Pattern HMS_TS =
            java.util.regex.Pattern.compile("\\[(\\d{1,2}):(\\d{1,2}):(\\d{1,2})\\]");

    /**
     * 时间戳归一化（公开静态便于单测）：LLM 偶发输出 [320s] / [1:15:30]，
     * 前端胶囊与质检器均只识别 [mm:ss]，统一归一保证时间戳可点击跳转
     */
    public static String normalizeTimestamps(String markdown) {
        if (markdown == null || markdown.isBlank()) {
            return markdown;
        }
        StringBuilder sb = new StringBuilder();
        java.util.regex.Matcher matcher = SECONDS_TS.matcher(markdown);
        while (matcher.find()) {
            matcher.appendReplacement(sb, java.util.regex.Matcher
                    .quoteReplacement(fmtTs(Long.parseLong(matcher.group(1)))));
        }
        matcher.appendTail(sb);
        String text = sb.toString();

        sb = new StringBuilder();
        matcher = HMS_TS.matcher(text);
        while (matcher.find()) {
            long total = Long.parseLong(matcher.group(1)) * 3600L
                    + Long.parseLong(matcher.group(2)) * 60L
                    + Long.parseLong(matcher.group(3));
            matcher.appendReplacement(sb, java.util.regex.Matcher.quoteReplacement(fmtTs(total)));
        }
        matcher.appendTail(sb);
        return sb.toString();
    }

    private static String fmtTs(long sec) {
        return String.format("[%02d:%02d]", sec / 60, sec % 60);
    }

    /** 质检反馈段（B26 阶段 1）：带具体缺陷清单定向重生成 */
    static String qualityFeedback(List<String> qualityDefects) {
        if (qualityDefects == null || qualityDefects.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder("\n[质检反馈] 上一版笔记经校验存在以下问题，请修正后重新输出完整笔记：\n");
        for (String defect : qualityDefects) {
            sb.append("- ").append(defect).append('\n');
        }
        return sb.toString();
    }
}
