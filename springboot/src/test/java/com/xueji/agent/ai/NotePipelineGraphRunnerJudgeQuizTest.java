package com.xueji.agent.ai;

import com.alibaba.cloud.ai.graph.CompiledGraph;
import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.OverAllStateFactory;
import com.alibaba.cloud.ai.graph.StateGraph;
import com.alibaba.cloud.ai.graph.action.AsyncNodeAction;
import com.alibaba.cloud.ai.graph.action.NodeAction;
import com.alibaba.cloud.ai.graph.state.strategy.ReplaceStrategy;
import com.xueji.agent.ai.ContentReviewService.Verdict;
import com.xueji.agent.ai.NotePipelineGraphRunner.StageListener;
import com.xueji.agent.ai.QuizAgentService.QuizQuestion;
import com.xueji.agent.domain.entity.Course;
import com.xueji.agent.domain.entity.CourseTranscriptSegment;
import com.xueji.agent.service.CourseQuizService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import java.lang.reflect.Field;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static com.alibaba.cloud.ai.graph.StateGraph.END;
import static com.alibaba.cloud.ai.graph.StateGraph.START;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 判题 Agent 节点语义单测（判题终局保留合格题修复）：
 * ①轮次耗尽且评审 REVISE 但代码闸有合格题 → 照常落库，评审意见记入 quizIssue
 * ②代码闸 0 题存活 → 不落库，quizIssue 为失败文案
 * ③写题产物为空早退 → 清空 judgeFeedback（防残留标记引发条件边反复回写）
 * 状态构造复用 SaaGraphCompatibilityTest 的图驱动方式（seed 节点灌入），私有节点经 ReflectionTestUtils 调用
 */
class NotePipelineGraphRunnerJudgeQuizTest {

    private final ContentReviewService contentReviewService = mock(ContentReviewService.class);
    private final CourseQuizService courseQuizService = mock(CourseQuizService.class);
    private final QuizPlanService quizPlanService = mock(QuizPlanService.class);
    private final NotePipelineGraphRunner runner = new NotePipelineGraphRunner();

    private final Course course = new Course().setId(32L).setUserId(5L).setTitle("video_9 测试课");
    private final List<CourseTranscriptSegment> transcript = List.of(
            new CourseTranscriptSegment().setCourseId(32L).setStartSec(0).setEndSec(30).setSort(0)
                    .setText("四级翻译题主要考查中国的历史文化与社会发展内容，答题时需要注意时态和语序的转换"));

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(runner, "contentReviewService", contentReviewService);
        ReflectionTestUtils.setField(runner, "courseQuizService", courseQuizService);
        ReflectionTestUtils.setField(runner, "quizPlanService", quizPlanService);
    }

    @Test
    void terminalReviseShouldKeepQualifiedQuestions() throws Exception {
        when(quizPlanService.questionsText(any())).thenReturn("1. 题面");
        when(contentReviewService.reviewQuiz(anyString(), isNull(), anyString()))
                .thenReturn(ContentReviewService.parseVerdict(
                        "{\"verdict\": \"REVISE\", \"issues\": [\"评审意见A\"]}"));

        OverAllState state = runJudge(Map.of(
                "quizDraft", List.of(goodQuestion()),
                "points", List.of(),
                "quizRounds", 1,
                "judgeFeedback", List.of("上一轮反馈")));

        // 评审 REVISE 但 kept 非空：照常落库
        ArgumentCaptor<List<QuizAgentService.QuizQuestion>> captor = ArgumentCaptor.forClass(List.class);
        verify(courseQuizService).saveQuizQuestions(any(Course.class), captor.capture());
        assertThat(captor.getValue()).hasSize(1);
        assertThat(captor.getValue().get(0).getQuestion()).contains("四级翻译题");
        // 评审意见仅记录，不否决
        assertThat(state.<String>value("quizIssue").orElse("")).contains("已按现状入库").contains("评审意见A");
        // 终局清空回写标记
        assertThat(state.<List<String>>value("judgeFeedback").orElse(List.of("x"))).isEmpty();
        verify(contentReviewService).reviewQuiz(anyString(), isNull(), anyString());
    }

    @Test
    void zeroKeptShouldFailWithoutSaving() throws Exception {
        QuizQuestion outOfRange = new QuizQuestion();
        setField(outOfRange, "question", "与材料无关的题面，保证被越界规则之外的规则处理也没关系");
        setField(outOfRange, "answer", "答案");
        setField(outOfRange, "sourceSec", 99999);

        OverAllState state = runJudge(Map.of(
                "quizDraft", List.of(outOfRange),
                "points", List.of(),
                "quizRounds", 1,
                "judgeFeedback", List.of()));

        // 代码闸 0 题存活：不落库、不评审，报失败文案
        verify(courseQuizService, never()).saveQuizQuestions(any(Course.class), any());
        verify(contentReviewService, never()).reviewQuiz(anyString(), any(), any());
        assertThat(state.<String>value("quizIssue").orElse(""))
                .isEqualTo("课后习题生成失败：重写后仍无合格题目");
        assertThat(state.<List<String>>value("judgeFeedback").orElse(List.of("x"))).isEmpty();
    }

    @Test
    void emptyDraftShouldClearResidualJudgeFeedback() throws Exception {
        OverAllState state = runJudge(Map.of(
                "quizDraft", List.of(),
                "points", List.of(),
                "quizRounds", 1,
                "judgeFeedback", List.of("残留的判题反馈")));

        // 早退必须清空标记：否则条件边据非空 feedback 反复回 writeQuiz
        assertThat(state.<List<String>>value("judgeFeedback").orElse(List.of("x"))).isEmpty();
        verify(courseQuizService, never()).saveQuizQuestions(any(Course.class), any());
        verify(contentReviewService, never()).reviewQuiz(anyString(), any(), any());
    }

    // ---- 工具 ----

    private QuizQuestion goodQuestion() throws Exception {
        QuizQuestion q = new QuizQuestion();
        setField(q, "question", "四级翻译题主要考查哪类内容？答题时需要注意什么？");
        setField(q, "answer", "主要考查中国的历史文化与社会发展内容，注意时态和语序的转换");
        setField(q, "analysis", "转写在开头即点明考查范围与答题要点");
        setField(q, "sourceSec", 10);
        return q;
    }

    /** 图驱动：seed 节点灌入初始 state → judge 节点经反射调用私有 judgeQuizNode */
    private OverAllState runJudge(Map<String, Object> seed) throws Exception {
        OverAllStateFactory factory = () -> new OverAllState().registerKeyAndStrategy(Map.ofEntries(
                Map.entry("points", new ReplaceStrategy()),
                Map.entry("quizPlan", new ReplaceStrategy()),
                Map.entry("quizDraft", new ReplaceStrategy()),
                Map.entry("judgeFeedback", new ReplaceStrategy()),
                Map.entry("quizRounds", new ReplaceStrategy()),
                Map.entry("quizIssue", new ReplaceStrategy())));
        StageListener noop = (stage, text) -> { };
        StateGraph graph = new StateGraph(factory)
                .addNode("seed", AsyncNodeAction.node_async((NodeAction) state -> seed))
                .addNode("judge", AsyncNodeAction.node_async((NodeAction) state ->
                        ReflectionTestUtils.invokeMethod(runner, "judgeQuizNode",
                                state, course, transcript, 2241, null, noop)))
                .addEdge(START, "seed")
                .addEdge("seed", "judge")
                .addEdge("judge", END);
        return graph.compile().invoke(new HashMap<>())
                .orElseThrow(() -> new AssertionError("判题节点未产出状态"));
    }

    private static void setField(Object target, String name, Object value) throws Exception {
        Field f = target.getClass().getDeclaredField(name);
        f.setAccessible(true);
        f.set(target, value);
    }
}
