package com.xueji.agent.ai;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.xueji.agent.domain.entity.Course;
import com.xueji.agent.domain.entity.CourseTranscriptSegment;
import com.xueji.agent.domain.entity.Note;
import com.xueji.agent.domain.entity.QuestionRecord;
import com.xueji.agent.domain.entity.SimilarQuestion;
import com.xueji.agent.mapper.CourseMapper;
import com.xueji.agent.mapper.CourseTranscriptSegmentMapper;
import com.xueji.agent.mapper.NoteMapper;
import com.xueji.agent.mapper.QuestionRecordMapper;
import com.xueji.agent.mapper.SimilarQuestionMapper;
import com.xueji.agent.service.impl.QuestionVectorStoreService;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import redis.clients.jedis.JedisPooled;
import redis.clients.jedis.params.ScanParams;
import redis.clients.jedis.resps.ScanResult;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadPoolExecutor;

/**
 * RAG 学习资料统一摄取（PRD §3.6 入库范围）：题目（QuestionVectorStoreService）、
 * 相似题（similar_question，文档 ID sq: 前缀）、笔记（按小节切块）、网课转写分段（带时间戳元数据）。
 * 文档 ID 约定：题目 = q:{主键}；相似题 = sq:{主键}；笔记 = note:{id}:{块序号}；转写 = transcript:c{课程ID}:{分段ID}。
 * 所有展示信息（标题 / 时间戳）直接内嵌在向量化文本中，元数据只承载过滤与来源标记。
 */
@Slf4j
@Service
public class RagIngestService {

    /** 笔记正文超过该长度时按小节切块 */
    static final int CHUNK_THRESHOLD = 1200;

    /** 笔记切块上限，超出部分并入末块，防止内容遗漏 */
    static final int MAX_NOTE_CHUNKS = 30;

    /** 单块字符上限（embedding 模型上下文内留足余量） */
    static final int MAX_CHUNK_CHARS = 4000;

    private static final String DOC_PREFIX_NOTE = "note:";
    private static final String DOC_PREFIX_TRANSCRIPT = "transcript:c";
    private static final String DOC_PREFIX_SIMILAR = "sq:";

    @Resource
    private VectorStore vectorStore;

    @Resource(name = "courseExecutor")
    private ThreadPoolExecutor courseExecutor;

    @Resource
    private QuestionVectorStoreService questionVectorStoreService;

    @Resource
    private QuestionRecordMapper questionRecordMapper;

    @Resource
    private SimilarQuestionMapper similarQuestionMapper;

    @Resource
    private NoteMapper noteMapper;

    @Resource
    private CourseMapper courseMapper;

    @Resource
    private CourseTranscriptSegmentMapper transcriptSegmentMapper;

    @Value("${xj.vector.redis.host:127.0.0.1}")
    private String vectorRedisHost;

    @Value("${xj.vector.redis.port:6380}")
    private int vectorRedisPort;

    // ---- 笔记 ----

    /** 异步入库 / 重建一篇笔记（内容变更后块数可能变化，内部先清旧块） */
    public void ingestNoteAsync(Note note) {
        courseExecutor.execute(() -> {
            try {
                ingestNote(note);
            } catch (Exception e) {
                log.warn("笔记向量化入库失败, noteId={}", note.getId(), e);
            }
        });
    }

    public void ingestNote(Note note) {
        if (note.getId() == null) {
            return;
        }
        List<String> chunks = buildNoteChunks(note.getTitle(), note.getContent());
        if (chunks.isEmpty()) {
            return;
        }
        removeByPattern(DOC_PREFIX_NOTE + note.getId() + ":*");
        List<Document> docs = new ArrayList<>();
        for (int i = 0; i < chunks.size(); i++) {
            docs.add(new Document(DOC_PREFIX_NOTE + note.getId() + ":" + i, chunks.get(i), Map.of(
                    "userId", String.valueOf(note.getUserId()),
                    "type", "note",
                    "noteId", String.valueOf(note.getId()))));
        }
        vectorStore.add(docs);
        log.info("笔记已向量化入库, noteId={}, 块数={}", note.getId(), chunks.size());
    }

    public void removeNote(Long noteId) {
        if (noteId == null) {
            return;
        }
        try {
            removeByPattern(DOC_PREFIX_NOTE + noteId + ":*");
        } catch (Exception e) {
            log.warn("笔记向量清理失败, noteId={}", noteId, e);
        }
    }

    // ---- 网课转写 ----

    /** 异步入库一门课程的转写分段（流水线重跑先清旧分段，防止孤儿向量） */
    public void ingestCourseTranscriptsAsync(Course course, List<CourseTranscriptSegment> segments) {
        courseExecutor.execute(() -> {
            try {
                ingestCourseTranscripts(course, segments);
            } catch (Exception e) {
                log.warn("网课转写向量化入库失败, courseId={}", course.getId(), e);
            }
        });
    }

    public void ingestCourseTranscripts(Course course, List<CourseTranscriptSegment> segments) {
        if (segments == null || segments.isEmpty()) {
            return;
        }
        removeByPattern(DOC_PREFIX_TRANSCRIPT + course.getId() + ":*");
        List<Document> docs = new ArrayList<>();
        for (CourseTranscriptSegment segment : segments) {
            String text = buildTranscriptText(segment, course.getTitle());
            if (text.isBlank()) {
                continue;
            }
            docs.add(new Document(DOC_PREFIX_TRANSCRIPT + course.getId() + ":" + segment.getId(), text, Map.of(
                    "userId", String.valueOf(course.getUserId()),
                    "type", "transcript",
                    "courseId", String.valueOf(course.getId()),
                    "tsSec", String.valueOf(segment.getStartSec()))));
        }
        if (docs.isEmpty()) {
            return;
        }
        vectorStore.add(docs);
        log.info("网课转写已向量化入库, courseId={}, 分段数={}", course.getId(), docs.size());
    }

    public void removeCourseTranscripts(Long courseId) {
        if (courseId == null) {
            return;
        }
        try {
            removeByPattern(DOC_PREFIX_TRANSCRIPT + courseId + ":*");
        } catch (Exception e) {
            log.warn("网课转写向量清理失败, courseId={}", courseId, e);
        }
    }

    // ---- 相似题（AI 生成）----

    /** 异步入库 / 重建一条相似题 */
    public void ingestSimilarQuestionAsync(SimilarQuestion similar) {
        courseExecutor.execute(() -> {
            try {
                ingestSimilarQuestion(similar);
            } catch (Exception e) {
                log.warn("相似题向量化入库失败, similarQuestionId={}", similar.getId(), e);
            }
        });
    }

    public void ingestSimilarQuestion(SimilarQuestion similar) {
        if (similar.getId() == null) {
            return;
        }
        String text = buildSimilarText(similar);
        if (text.isBlank()) {
            return;
        }
        Document document = new Document(DOC_PREFIX_SIMILAR + similar.getId(), text, Map.of(
                "userId", String.valueOf(similar.getUserId()),
                "type", "question",
                "subject", similar.getSubject() == null || similar.getSubject().isBlank() ? "未分类" : similar.getSubject(),
                "isWrong", "未判定"));
        vectorStore.add(List.of(document));
        log.info("相似题已向量化入库, similarQuestionId={}", similar.getId());
    }

    public void removeSimilar(Long similarId) {
        if (similarId == null) {
            return;
        }
        try {
            vectorStore.delete(List.of(DOC_PREFIX_SIMILAR + similarId));
        } catch (Exception e) {
            log.warn("相似题向量删除失败, similarQuestionId={}", similarId, e);
        }
    }

    /** 相似题向量化文本：与拍照题目同构（题目 / 解答 / 解析），供 rag_search 生成更多相似题时召回 */
    public static String buildSimilarText(SimilarQuestion similar) {
        StringBuilder sb = new StringBuilder();
        sb.append("题目：").append(QuestionVectorStoreService.stripMarks(similar.getQuestionText()));
        if (similar.getAnswer() != null && !similar.getAnswer().isBlank()) {
            sb.append("\n解答：").append(QuestionVectorStoreService.stripMarks(similar.getAnswer()));
        }
        if (similar.getAnalysis() != null && !similar.getAnalysis().isBlank()) {
            sb.append("\n解析：").append(QuestionVectorStoreService.stripMarks(similar.getAnalysis()));
        }
        return sb.toString();
    }

    // ---- 补漏（定时任务调用）----

    /**
     * 回填最近一次水位之后有变动的题目 / 笔记 / 课程转写；checkpoint 为 null 表示全量回填。
     * 摄取按文档 ID 幂等（同 ID 覆盖），失败项由下一轮再次覆盖。返回处理的实体数。
     */
    public int repairSince(LocalDateTime checkpoint) {
        int processed = 0;

        List<QuestionRecord> questions = questionRecordMapper.selectList(new QueryWrapper<QuestionRecord>()
                .gt(checkpoint != null, "updated_at", checkpoint));
        for (QuestionRecord record : questions) {
            if (Integer.valueOf(1).equals(record.getDeleted())) {
                questionVectorStoreService.remove(record.getId());
            } else {
                questionVectorStoreService.ingest(record);
            }
            processed++;
        }

        List<SimilarQuestion> similars = similarQuestionMapper.selectList(new QueryWrapper<SimilarQuestion>()
                .gt(checkpoint != null, "updated_at", checkpoint));
        for (SimilarQuestion similar : similars) {
            if (Integer.valueOf(1).equals(similar.getDeleted())) {
                removeSimilar(similar.getId());
            } else {
                ingestSimilarQuestion(similar);
            }
            processed++;
        }

        List<Note> notes = noteMapper.selectList(new QueryWrapper<Note>()
                .gt(checkpoint != null, "updated_at", checkpoint));
        for (Note note : notes) {
            if (Integer.valueOf(1).equals(note.getDeleted())) {
                removeNote(note.getId());
            } else {
                ingestNote(note);
            }
            processed++;
        }

        List<Course> courses = courseMapper.selectList(new QueryWrapper<Course>()
                .gt(checkpoint != null, "updated_at", checkpoint)
                .eq("deleted", 0));
        for (Course course : courses) {
            List<CourseTranscriptSegment> segments = transcriptSegmentMapper.selectList(
                    new QueryWrapper<CourseTranscriptSegment>()
                            .eq("course_id", course.getId())
                            .orderByAsc("sort"));
            ingestCourseTranscripts(course, segments);
            processed++;
        }
        return processed;
    }

    // ---- 文本组装（公开静态，便于单元测试）----

    /**
     * 笔记向量化文本：短笔记（或无小节结构）单块；长笔记按一级小节（##）切块，
     * 每块前置笔记标题保证上下文完整；超出上限的小节并入末块
     */
    public static List<String> buildNoteChunks(String title, String content) {
        List<String> chunks = new ArrayList<>();
        String body = content == null ? "" : content.trim();
        if (body.isBlank()) {
            return chunks;
        }
        if (body.length() <= CHUNK_THRESHOLD || !body.contains("\n## ")) {
            chunks.add(truncate("笔记《" + title + "》\n" + body));
            return chunks;
        }
        List<String> sections = new ArrayList<>();
        for (String part : body.split("\n## ")) {
            if (!part.isBlank()) {
                sections.add(part);
            }
        }
        int cap = Math.min(sections.size(), MAX_NOTE_CHUNKS);
        for (int i = 0; i < cap; i++) {
            String section = i == 0 ? sections.get(i) : "## " + sections.get(i);
            // 超出上限的小节并入末块
            if (i == cap - 1) {
                StringBuilder merged = new StringBuilder(section);
                for (int j = cap; j < sections.size(); j++) {
                    merged.append("\n## ").append(sections.get(j));
                }
                section = merged.toString();
            }
            chunks.add(truncate("笔记《" + title + "》\n" + section));
        }
        return chunks;
    }

    /** 转写分段向量化文本：课程名 + 起止时间戳 + 文本（修正版优先，B26 阶段 1） */
    public static String buildTranscriptText(CourseTranscriptSegment segment, String courseTitle) {
        String text = segment.getTextCorrected() != null ? segment.getTextCorrected() : segment.getText();
        if (text == null || text.isBlank()) {
            return "";
        }
        return "网课《" + courseTitle + "》[" + mmss(segment.getStartSec()) + "-" + mmss(segment.getEndSec()) + "]\n"
                + text;
    }

    static String mmss(int sec) {
        if (sec < 0) {
            sec = 0;
        }
        return String.format("%02d:%02d", sec / 60, sec % 60);
    }

    private static String truncate(String text) {
        return text.length() <= MAX_CHUNK_CHARS ? text : text.substring(0, MAX_CHUNK_CHARS) + "…";
    }

    /**
     * 按文档 ID 模式清理向量（向量库所有键共用统一前缀，键 = 前缀 + 文档 ID）。
     * Spring AI VectorStore 接口只支持按精确 ID 删除，块数变化的重建需要按前缀扫描。
     */
    private void removeByPattern(String docIdPattern) {
        String keyPattern = "rag:question:" + docIdPattern;
        try (JedisPooled jedis = new JedisPooled(vectorRedisHost, vectorRedisPort)) {
            List<String> keys = new ArrayList<>();
            String cursor = "0";
            ScanParams params = new ScanParams().match(keyPattern).count(500);
            do {
                ScanResult<String> result = jedis.scan(cursor, params);
                keys.addAll(result.getResult());
                cursor = result.getCursor();
            } while (!"0".equals(cursor));
            if (!keys.isEmpty()) {
                jedis.del(keys.toArray(new String[0]));
                log.info("向量清理完成, pattern={}, 条数={}", keyPattern, keys.size());
            }
        } catch (Exception e) {
            log.warn("向量按前缀清理失败, pattern={}", keyPattern, e);
        }
    }
}
