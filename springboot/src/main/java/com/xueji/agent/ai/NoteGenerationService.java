package com.xueji.agent.ai;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.xueji.agent.ai.prompt.AgentPrompts;
import com.xueji.agent.ai.tool.AsrSegment;
import com.xueji.agent.domain.enums.CourseStatus;
import com.xueji.agent.domain.entity.Course;
import com.xueji.agent.domain.entity.CourseFrame;
import com.xueji.agent.domain.entity.CourseTranscriptSegment;
import com.xueji.agent.domain.entity.Note;
import com.xueji.agent.mapper.NoteMapper;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 网课笔记生成（流水线末端 LLM 阶段）：
 * 语音转写分段 + 关键帧识别文本 + 用户期望 → COURSE_TRANSCRIPT_PROMPT → Markdown 笔记草稿 → note 表入库。
 * 生成的笔记直接入库（source_type = 网课 AI 生成）；确认卡片方针确定后再补确认环节。
 */
@Slf4j
@Service
public class NoteGenerationService {

    @Resource
    private ChatClient chatClient;

    @Resource
    private NoteMapper noteMapper;

    /**
     * 生成笔记并入库（重试幂等：先删除该课程此前生成的 AI 笔记再插入）。
     * 返回笔记 ID；生成失败抛出异常，由调用方决定降级方式
     */
    public Long generateAndSaveNote(Course course, List<CourseTranscriptSegment> transcript,
                                    List<CourseFrame> frames, int durationSec) {
        String markdown = generate(course, transcript, frames, durationSec);

        // 幂等：清掉旧 AI 笔记
        noteMapper.delete(new QueryWrapper<Note>()
                .eq("course_id", course.getId())
                .eq("source_type", 1));

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
        log.info("AI 笔记已生成入库, courseId={}, noteId={}, 字数={}", course.getId(), note.getId(), markdown.length());
        return note.getId();
    }

    /**
     * 调 LLM 生成笔记 Markdown
     */
    public String generate(Course course, List<CourseTranscriptSegment> transcript,
                           List<CourseFrame> frames, int durationSec) {
        String userContent = buildUserContent(transcript, frames, course.getExpectations(), durationSec);
        String markdown = chatClient.prompt()
                .system(AgentPrompts.COURSE_TRANSCRIPT_PROMPT)
                .user(userContent)
                // 独立会话空间：笔记生成的上下文不与用户对话混用
                .advisors(advisorSpec -> advisorSpec.param(ChatMemory.CONVERSATION_ID, "course-note-" + course.getId()))
                .call()
                .content();
        if (markdown == null || markdown.isBlank()) {
            throw new IllegalStateException("LLM 未返回笔记内容");
        }
        log.info("AI 笔记生成完成, courseId={}, 字数={}", course.getId(), markdown.length());
        return markdown;
    }

    /**
     * 组装用户消息：转写分段（带时间戳）+ 关键帧识别 + 用户期望。
     * 公开静态方法，便于单元测试
     */
    public static String buildUserContent(List<CourseTranscriptSegment> transcript,
                                          List<CourseFrame> frames, String expectations, int durationSec) {
        StringBuilder sb = new StringBuilder();
        sb.append("视频时长：").append(durationSec).append(" 秒。\n\n");

        sb.append("[语音转写文本（句级分段，含起止秒）]\n");
        if (transcript == null || transcript.isEmpty()) {
            sb.append("（本视频未获得语音转写结果）\n");
        } else {
            for (CourseTranscriptSegment segment : transcript) {
                sb.append("[").append(segment.getStartSec()).append("-").append(segment.getEndSec()).append("s] ")
                        .append(segment.getText()).append("\n");
            }
        }

        sb.append("\n[画面识别文本（关键帧，含时间戳）]\n");
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

        if (StringUtils.hasText(expectations)) {
            sb.append("\n[用户的特别要求]\n").append(expectations.trim()).append("\n");
            sb.append("生成笔记时优先满足以上要求；若要求与视频内容无关，请自然忽略，不要虚构内容。\n");
        }
        return sb.toString();
    }
}
