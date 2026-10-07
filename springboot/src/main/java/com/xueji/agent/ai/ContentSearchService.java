package com.xueji.agent.ai;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.xueji.agent.domain.entity.ContentDocument;
import com.xueji.agent.domain.entity.ContentKnowledgePoint;
import com.xueji.agent.domain.entity.ContentSection;
import com.xueji.agent.domain.entity.Course;
import com.xueji.agent.domain.entity.CourseTranscriptSegment;
import com.xueji.agent.mapper.ContentDocumentMapper;
import com.xueji.agent.mapper.ContentKnowledgePointMapper;
import com.xueji.agent.mapper.ContentSectionMapper;
import com.xueji.agent.mapper.CourseMapper;
import com.xueji.agent.mapper.CourseTranscriptSegmentMapper;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 内容检索服务（B26 阶段 4 Content Tools）：在用户的 ContentDocument（章节 / 知识点）与转写原文中做关键词检索。
 * 返回紧凑文本（每类限条数、片段截断，token 控制），命中行带 [mm:ss] 时间戳供 LLM 引用（前端渲染可点击胶囊）
 */
@Slf4j
@Service
public class ContentSearchService {

    /** 每类命中上限与片段截断长度（token 控制：工具返回「摘要」而非大段产物） */
    static final int MAX_HITS_PER_KIND = 5;
    static final int SNIPPET_MAX_CHARS = 120;

    @Resource
    private CourseMapper courseMapper;

    @Resource
    private ContentDocumentMapper documentMapper;

    @Resource
    private ContentSectionMapper sectionMapper;

    @Resource
    private ContentKnowledgePointMapper knowledgePointMapper;

    @Resource
    private CourseTranscriptSegmentMapper transcriptMapper;

    /**
     * 检索入口：返回给 LLM 的文本结果（未命中返回明确提示，不抛异常）
     */
    public String search(Long userId, String keyword) {
        if (keyword == null || keyword.isBlank()) {
            return "SEARCH_EMPTY: 关键词为空";
        }
        String kw = keyword.trim();
        List<Course> courses = courseMapper.selectList(new QueryWrapper<Course>().eq("user_id", userId));
        if (courses.isEmpty()) {
            return "（你还没有网课记录，可以先在学习台上传或转写视频）";
        }
        Map<Long, Course> courseById = new HashMap<>();
        List<Long> courseIds = new ArrayList<>();
        for (Course course : courses) {
            courseById.put(course.getId(), course);
            courseIds.add(course.getId());
        }

        List<ContentDocument> documents = documentMapper.selectList(
                new QueryWrapper<ContentDocument>().eq("user_id", userId));
        List<Long> documentIds = new ArrayList<>();
        Map<Long, Long> courseByDocumentId = new HashMap<>();
        for (ContentDocument document : documents) {
            documentIds.add(document.getId());
            courseByDocumentId.put(document.getId(), document.getCourseId());
        }

        List<ContentSection> sections = documentIds.isEmpty() ? List.of()
                : sectionMapper.selectList(new QueryWrapper<ContentSection>()
                        .in("document_id", documentIds)
                        .and(w -> w.like("title", kw).or().like("summary", kw))
                        .last("LIMIT " + MAX_HITS_PER_KIND));
        List<ContentKnowledgePoint> points = documentIds.isEmpty() ? List.of()
                : knowledgePointMapper.selectList(new QueryWrapper<ContentKnowledgePoint>()
                        .in("document_id", documentIds)
                        .and(w -> w.like("name", kw).or().like("detail", kw))
                        .last("LIMIT " + MAX_HITS_PER_KIND));
        List<CourseTranscriptSegment> transcript = transcriptMapper.selectList(
                new QueryWrapper<CourseTranscriptSegment>()
                        .in("course_id", courseIds)
                        .and(w -> w.like("text", kw).or().like("text_corrected", kw))
                        .orderByAsc("sort")
                        .last("LIMIT " + MAX_HITS_PER_KIND));

        return formatHits(kw, courseById, courseByDocumentId, sections, points, transcript);
    }

    /**
     * 命中结果 → 紧凑文本（公开静态便于单测）
     */
    public static String formatHits(String keyword, Map<Long, Course> courseById,
                                    Map<Long, Long> courseByDocumentId,
                                    List<ContentSection> sections, List<ContentKnowledgePoint> points,
                                    List<CourseTranscriptSegment> transcript) {
        StringBuilder sb = new StringBuilder();
        sb.append("[内容检索结果] 关键词「").append(keyword).append("」\n");
        int total = 0;
        for (ContentSection section : sections) {
            Course course = courseById.get(courseByDocumentId.get(section.getDocumentId()));
            sb.append("- 《").append(course == null ? "未知课程" : course.getTitle()).append("》章节 ")
                    .append(fmt(section.getStartSec())).append("-").append(fmt(section.getEndSec()))
                    .append(" ").append(section.getTitle());
            if (section.getSummary() != null && !section.getSummary().isBlank()) {
                sb.append("：").append(snippet(section.getSummary()));
            }
            sb.append('\n');
            total++;
        }
        for (ContentKnowledgePoint point : points) {
            Course course = courseById.get(courseByDocumentId.get(point.getDocumentId()));
            sb.append("- 《").append(course == null ? "未知课程" : course.getTitle()).append("》知识点 ");
            if (point.getTimeSec() != null) {
                sb.append(fmt(point.getTimeSec())).append(" ");
            }
            sb.append(point.getName());
            if (point.getDetail() != null && !point.getDetail().isBlank()) {
                sb.append("：").append(snippet(point.getDetail()));
            }
            sb.append('\n');
            total++;
        }
        for (CourseTranscriptSegment segment : transcript) {
            Course course = courseById.get(segment.getCourseId());
            String text = segment.getTextCorrected() != null ? segment.getTextCorrected() : segment.getText();
            sb.append("- 《").append(course == null ? "未知课程" : course.getTitle()).append("》转写 ")
                    .append(fmt(segment.getStartSec())).append(" \"").append(snippet(text)).append("\"\n");
            total++;
        }
        if (total == 0) {
            sb.append("（未检索到相关内容，可建议用户换个关键词，或确认对应网课已处理完成）");
        }
        return sb.toString();
    }

    /** 片段截断（命中词前后文控制） */
    static String snippet(String text) {
        if (text == null) {
            return "";
        }
        return text.length() <= SNIPPET_MAX_CHARS ? text : text.substring(0, SNIPPET_MAX_CHARS) + "…";
    }

    /** 秒 → [mm:ss] */
    static String fmt(Integer sec) {
        if (sec == null || sec < 0) {
            return "[00:00]";
        }
        return String.format("[%02d:%02d]", sec / 60, sec % 60);
    }
}
