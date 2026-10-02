package com.xueji.agent.ai;

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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.ThreadPoolExecutor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * RAG 统一摄取：笔记分块规则、转写时间戳文本、补漏回填的选择逻辑
 */
@ExtendWith(MockitoExtension.class)
class RagIngestServiceTest {

    @Mock
    private VectorStore vectorStore;

    @Mock
    private ThreadPoolExecutor courseExecutor;

    @Mock
    private QuestionVectorStoreService questionVectorStoreService;

    @Mock
    private QuestionRecordMapper questionRecordMapper;

    @Mock
    private SimilarQuestionMapper similarQuestionMapper;

    @Mock
    private NoteMapper noteMapper;

    @Mock
    private CourseMapper courseMapper;

    @Mock
    private CourseTranscriptSegmentMapper transcriptSegmentMapper;

    private RagIngestService service;

    @BeforeEach
    void setUp() {
        service = new RagIngestService();
        ReflectionTestUtils.setField(service, "vectorStore", vectorStore);
        ReflectionTestUtils.setField(service, "courseExecutor", courseExecutor);
        ReflectionTestUtils.setField(service, "questionVectorStoreService", questionVectorStoreService);
        ReflectionTestUtils.setField(service, "questionRecordMapper", questionRecordMapper);
        ReflectionTestUtils.setField(service, "similarQuestionMapper", similarQuestionMapper);
        ReflectionTestUtils.setField(service, "noteMapper", noteMapper);
        ReflectionTestUtils.setField(service, "courseMapper", courseMapper);
        ReflectionTestUtils.setField(service, "transcriptSegmentMapper", transcriptSegmentMapper);
        ReflectionTestUtils.setField(service, "vectorRedisHost", "127.0.0.1");
        ReflectionTestUtils.setField(service, "vectorRedisPort", 6380);
        // 线程池 mock 直接在当前线程执行，便于验证摄取结果
        lenient().doAnswer(inv -> {
            ((Runnable) inv.getArgument(0)).run();
            return null;
        }).when(courseExecutor).execute(any(Runnable.class));
    }

    // ---- 笔记分块 ----

    @Test
    void shortNoteShouldBeSingleChunk() {
        List<String> chunks = RagIngestService.buildNoteChunks("罗尔定理",
                "若函数在闭区间连续、开区间可导且端点等值，则存在驻点。");

        assertThat(chunks).hasSize(1);
        assertThat(chunks.get(0)).startsWith("笔记《罗尔定理》").contains("驻点");
    }

    @Test
    void blankNoteShouldProduceNoChunks() {
        assertThat(RagIngestService.buildNoteChunks("空笔记", "  ")).isEmpty();
        assertThat(RagIngestService.buildNoteChunks("空笔记", null)).isEmpty();
    }

    @Test
    void longNoteShouldChunkBySections() {
        StringBuilder body = new StringBuilder("# 课程 — 课程笔记\n\n导语部分。");
        for (int i = 1; i <= 8; i++) {
            body.append("\n\n## 知识点 ").append(i).append("\n\n").append("要点内容。".repeat(120));
        }
        List<String> chunks = RagIngestService.buildNoteChunks("标题", body.toString());

        assertThat(chunks).hasSize(9); // 导语 + 8 个小节
        assertThat(chunks.get(0)).startsWith("笔记《标题》").contains("导语部分");
        assertThat(chunks.get(1)).startsWith("笔记《标题》").contains("## 知识点 1");
        assertThat(chunks.get(8)).startsWith("笔记《标题》").contains("## 知识点 8");
    }

    @Test
    void chunksShouldBeCappedAndMerged() {
        StringBuilder body = new StringBuilder("导语。");
        for (int i = 1; i <= 40; i++) {
            // 每节内容足够长以越过单块阈值
            body.append("\n\n## 小节 ").append(i).append("\n\n").append("第 ").append(i).append(" 节的正文内容。".repeat(40));
        }
        List<String> chunks = RagIngestService.buildNoteChunks("标题", body.toString());

        assertThat(chunks).hasSize(RagIngestService.MAX_NOTE_CHUNKS);
        // 超限小节并入末块
        assertThat(chunks.get(chunks.size() - 1)).contains("小节 40");
    }

    // ---- 转写文本 ----

    @Test
    void transcriptTextShouldEmbedTitleAndTimestamps() {
        CourseTranscriptSegment segment = new CourseTranscriptSegment()
                .setStartSec(258).setEndSec(317).setText("今天讲罗尔定理。");

        String text = RagIngestService.buildTranscriptText(segment, "计算机科学 第 3 讲");

        assertThat(text).startsWith("网课《计算机科学 第 3 讲》[04:18-05:17]");
        assertThat(text).endsWith("今天讲罗尔定理。");
    }

    @Test
    void blankTranscriptShouldBeSkipped() {
        CourseTranscriptSegment blank = new CourseTranscriptSegment().setStartSec(0).setEndSec(5).setText(" ");
        assertThat(RagIngestService.buildTranscriptText(blank, "课程")).isEmpty();
    }

    // ---- 相似题（sq: 前缀）----

    @Test
    void similarQuestionShouldIngestWithSqPrefixAndQuestionType() {
        SimilarQuestion similar = new SimilarQuestion()
                .setId(3L).setUserId(5L).setQuestionText("**相似题**：求 $g(x)=e^x-x-1$ 的极值")
                .setAnswer("极小值为 -1").setSubject("数学");

        service.ingestSimilarQuestion(similar);

        @SuppressWarnings("unchecked")
        org.mockito.ArgumentCaptor<List<org.springframework.ai.document.Document>> captor =
                org.mockito.ArgumentCaptor.forClass((Class) List.class);
        verify(vectorStore).add(captor.capture());
        org.springframework.ai.document.Document doc = captor.getValue().get(0);
        // 与拍照题目（q: 前缀）区分两表自增主键
        assertThat(doc.getId()).isEqualTo("sq:3");
        assertThat(doc.getMetadata()).containsEntry("type", "question").containsEntry("subject", "数学");
        assertThat(doc.getText()).startsWith("题目：相似题 ：求").contains("解答：极小值为 -1");
    }

    @Test
    void removeSimilarShouldDeleteBySqId() {
        service.removeSimilar(3L);
        verify(vectorStore).delete(List.of("sq:3"));
    }

    // ---- 补漏回填 ----

    @Test
    void repairShouldIngestAliveAndRemoveDeleted() {
        when(questionRecordMapper.selectList(any())).thenReturn(List.of(
                new QuestionRecord().setId(1L).setUserId(5L).setDeleted(0).setQuestionText("题一"),
                new QuestionRecord().setId(2L).setUserId(5L).setDeleted(1).setQuestionText("题二")));
        when(similarQuestionMapper.selectList(any())).thenReturn(List.of(
                new SimilarQuestion().setId(21L).setUserId(5L).setQuestionText("相似题").setDeleted(0)));
        when(noteMapper.selectList(any())).thenReturn(List.of(
                new Note().setId(10L).setUserId(5L).setTitle("笔记A").setContent("内容A").setDeleted(0),
                new Note().setId(11L).setUserId(5L).setTitle("笔记B").setContent("内容B").setDeleted(1)));
        Course course = new Course().setId(20L).setUserId(5L).setTitle("网课C").setDeleted(0);
        when(courseMapper.selectList(any())).thenReturn(List.of(course));
        when(transcriptSegmentMapper.selectList(any())).thenReturn(List.of(
                new CourseTranscriptSegment().setId(30L).setCourseId(20L).setStartSec(0).setEndSec(60).setText("段落")));

        int processed = service.repairSince(LocalDateTime.of(2026, 10, 1, 0, 0));

        assertThat(processed).isEqualTo(6);
        verify(questionVectorStoreService).ingest(any(QuestionRecord.class));
        verify(questionVectorStoreService).remove(2L);
        verify(vectorStore, org.mockito.Mockito.times(3)).add(anyList()); // 相似题 1 块 + 笔记 1 块 + 转写 1 块（笔记 B 已删不入库）
    }

    @Test
    void groupNoteWithEmptyContentShouldBeSkipped() {
        when(questionRecordMapper.selectList(any())).thenReturn(List.of());
        when(noteMapper.selectList(any())).thenReturn(List.of(
                new Note().setId(12L).setUserId(5L).setTitle("空分组").setContent("").setDeleted(0)));
        when(courseMapper.selectList(any())).thenReturn(List.of());

        int processed = service.repairSince(null);

        assertThat(processed).isEqualTo(1);
        verify(vectorStore, never()).add(anyList());
    }
}
