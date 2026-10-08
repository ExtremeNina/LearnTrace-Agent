package com.xueji.agent.ai;

import com.alibaba.cloud.ai.graph.CompiledGraph;
import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.OverAllStateFactory;
import com.alibaba.cloud.ai.graph.StateGraph;
import com.alibaba.cloud.ai.graph.action.AsyncEdgeAction;
import com.alibaba.cloud.ai.graph.action.AsyncNodeAction;
import com.alibaba.cloud.ai.graph.action.EdgeAction;
import com.alibaba.cloud.ai.graph.action.NodeAction;
import com.alibaba.cloud.ai.graph.state.strategy.ReplaceStrategy;
import com.xueji.agent.ai.ContentReviewService.Role;
import com.xueji.agent.ai.ContentReviewService.Verdict;
import com.xueji.agent.ai.NoteGenerationService.NoteGenerationResult;
import com.xueji.agent.ai.QuizAgentService.QuizQuestion;
import com.xueji.agent.ai.QuizPlanService.PlanItem;
import com.xueji.agent.domain.entity.ContentDocument;
import com.xueji.agent.domain.entity.ContentKnowledgePoint;
import com.xueji.agent.domain.entity.ContentSection;
import com.xueji.agent.domain.entity.Course;
import com.xueji.agent.domain.entity.CourseFrame;
import com.xueji.agent.domain.entity.CourseTranscriptSegment;
import com.xueji.agent.domain.entity.UserProfile;
import com.xueji.agent.service.ContentDocumentService;
import com.xueji.agent.service.CourseQuizService;
import com.xueji.agent.service.ProfileService;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadPoolExecutor;

import static com.alibaba.cloud.ai.graph.StateGraph.END;
import static com.alibaba.cloud.ai.graph.StateGraph.START;

/**
 * 理解 - 评审 - 渲染 - 课后习题子流程（B27 三 Agent 重排，SAA StateGraph 驱动）：
 *
 * <pre>
 * START → understand → reviewL1 ─(PASS/耗尽)→ render → reviewL2 ─(PASS/耗尽)→ quizPlan → writeQuiz → judgeQuiz ─(PASS/耗尽)→ END
 *            ↑              └─(REVISE 首次)─┘             └─(REVISE 首次)─┘                          └─(REVISE 首次)─┘
 * </pre>
 *
 * 出题三 Agent（B27 重排：出题 Agent 在 AI 笔记生成并评审通过后启动，读取笔记 + 画像命题）：
 * - quizPlan（出题 Agent）：AI 笔记 + 画像 → 确认知识点数量 / 出题思路 / 题目编排（题量不设固定值，上限 100）
 * - writeQuiz（写题 Agent）：按规划逐题实现
 * - judgeQuiz（审题 Agent）：代码闸（QuizQualityChecker）+ LLM 评审（结合画像）；不合格反馈回写题 ≤1 轮
 * 媒体前置链路（上传 / 抽取 / ASR / OCR）仍由 CoursePipelineService 编排，不在本图内
 */
@Slf4j
@Component
public class NotePipelineGraphRunner {

    /** 阶段进度回调（由 CoursePipelineService 桥接到 COURSE 事件推送与课程 stage 持久化） */
    public interface StageListener {
        void onStage(String stage, String text);
    }

    /** 执行结果：笔记 / 习题问题汇总（NULL = 全部通过） */
    public static class RunResult {
        private String noteIssue;

        public String getNoteIssue() {
            return noteIssue;
        }
    }

    @Resource
    private ContentUnderstandingService contentUnderstandingService;

    @Resource
    private ContentDocumentService contentDocumentService;

    @Resource
    private NoteGenerationService noteGenerationService;

    @Resource
    private ContentReviewService contentReviewService;

    @Resource
    private QuizPlanService quizPlanService;

    @Resource
    private CourseQuizService courseQuizService;

    @Resource
    private ProfileService profileService;

    @Resource(name = "courseExecutor")
    private ThreadPoolExecutor courseExecutor;

    public RunResult run(Course course, List<CourseTranscriptSegment> transcript, List<CourseFrame> frames,
                         int durationSec, StageListener listener) throws Exception {
        UserProfile profile = profileService.getByUser(course.getUserId());

        OverAllStateFactory factory = () -> new OverAllState()
                .registerKeyAndStrategy(Map.ofEntries(
                        Map.entry("document", new ReplaceStrategy()),
                        Map.entry("sections", new ReplaceStrategy()),
                        Map.entry("points", new ReplaceStrategy()),
                        Map.entry("l1Verdict", new ReplaceStrategy()),
                        Map.entry("l1Revised", new ReplaceStrategy()),
                        Map.entry("feedback", new ReplaceStrategy()),
                        Map.entry("l2Rounds", new ReplaceStrategy()),
                        Map.entry("noteMarkdown", new ReplaceStrategy()),
                        Map.entry("noteIssue", new ReplaceStrategy()),
                        Map.entry("reviewSummary", new ReplaceStrategy()),
                        Map.entry("quizPlan", new ReplaceStrategy()),
                        Map.entry("quizDraft", new ReplaceStrategy()),
                        Map.entry("judgeFeedback", new ReplaceStrategy()),
                        Map.entry("quizRounds", new ReplaceStrategy()),
                        Map.entry("quizIssue", new ReplaceStrategy())));

        StateGraph graph = new StateGraph(factory)
                .addNode("quizPlan", AsyncNodeAction.node_async((NodeAction) state ->
                        quizPlanNode(state, course, profile, listener)))
                .addNode("understand", AsyncNodeAction.node_async((NodeAction) state ->
                        understandNode(course, transcript, durationSec, listener)))
                .addNode("reviewL1", AsyncNodeAction.node_async((NodeAction) state ->
                        reviewL1Node(state, course, profile, listener)))
                .addNode("render", AsyncNodeAction.node_async((NodeAction) state ->
                        renderNode(state, course, transcript, frames, durationSec, listener)))
                .addNode("reviewL2", AsyncNodeAction.node_async((NodeAction) state ->
                        reviewL2Node(state, course, profile, listener)))
                .addNode("writeQuiz", AsyncNodeAction.node_async((NodeAction) state ->
                        writeQuizNode(state, course, transcript, profile, listener)))
                .addNode("judgeQuiz", AsyncNodeAction.node_async((NodeAction) state ->
                        judgeQuizNode(state, course, transcript, durationSec, profile, listener)))
                .addEdge(START, "understand")
                .addEdge("understand", "reviewL1")
                .addConditionalEdges("reviewL1", AsyncEdgeAction.edge_async((EdgeAction) state -> {
                    boolean revise = "REVISE".equals(state.value("l1Verdict", ""));
                    boolean canRevise = Integer.valueOf(0).equals(state.<Integer>value("l1Revised").orElse(0));
                    return revise && canRevise ? "understand" : "render";
                }), Map.of("understand", "understand", "render", "render"))
                .addEdge("render", "reviewL2")
                .addConditionalEdges("reviewL2", AsyncEdgeAction.edge_async((EdgeAction) state -> {
                    List<String> feedback = state.<List<String>>value("feedback").orElse(List.of());
                    boolean canRevise = Integer.valueOf(1).equals(state.<Integer>value("l2Rounds").orElse(0));
                    return !feedback.isEmpty() && canRevise ? "render" : "quizPlan";
                }), Map.of("render", "render", "quizPlan", "quizPlan"))
                .addEdge("quizPlan", "writeQuiz")
                .addEdge("writeQuiz", "judgeQuiz")
                .addConditionalEdges("judgeQuiz", AsyncEdgeAction.edge_async((EdgeAction) state -> {
                    List<String> judgeFeedback = state.<List<String>>value("judgeFeedback").orElse(List.of());
                    boolean canRevise = Integer.valueOf(1).equals(state.<Integer>value("quizRounds").orElse(0));
                    return !judgeFeedback.isEmpty() && canRevise ? "writeQuiz" : END;
                }), Map.of("writeQuiz", "writeQuiz", END, END));

        CompiledGraph compiled = graph.compile();
        OverAllState finalState = compiled.invoke(new HashMap<>())
                .orElseThrow(() -> new IllegalStateException("理解 - 评审 - 渲染 - 出题子流程未产出状态"));

        RunResult result = new RunResult();
        String noteIssue = finalState.<String>value("noteIssue").orElse(null);
        String quizIssue = finalState.<String>value("quizIssue").orElse(null);
        result.noteIssue = noteIssue == null ? quizIssue
                : quizIssue == null ? noteIssue : noteIssue + "；" + quizIssue;
        return result;
    }

    /** 出题 Agent 节点（B27 重排）：AI 笔记（state.noteMarkdown）+ 画像 → 命题规划；失败不阻断（quizPlan 置空跳过出题） */
    private Map<String, Object> quizPlanNode(OverAllState state, Course course,
                                             UserProfile profile, StageListener listener) {
        Map<String, Object> update = new HashMap<>();
        try {
            listener.onStage("QUIZ_PLANNING", "出题 Agent 正在通读 AI 笔记规划课后习题…");
            String noteMarkdown = state.<String>value("noteMarkdown")
                    .orElseThrow(() -> new IllegalStateException("渲染节点未产出笔记"));
            List<PlanItem> plan = quizPlanService.planQuiz(noteMarkdown, profile);
            update.put("quizPlan", plan);
            log.info("命题规划完成, courseId={}, 条数={}", course.getId(), plan.size());
        } catch (Exception e) {
            log.warn("命题规划失败（跳过课后习题）, courseId={}", course.getId(), e);
            update.put("quizPlan", List.of());
            appendIssue(update, "课后习题生成失败：命题规划异常（" + e.getMessage() + "）");
        }
        return update;
    }

    /** 理解节点：内容理解 → ContentDocument 三表落库（重生成时 save 幂等覆盖） */
    private Map<String, Object> understandNode(Course course, List<CourseTranscriptSegment> transcript,
                                               int durationSec, StageListener listener) throws Exception {
        listener.onStage("UNDERSTANDING", "正在进行内容理解…");
        ContentUnderstanding understanding = contentUnderstandingService.understand(transcript, durationSec);
        contentDocumentService.save(course, understanding);
        ContentDocument document = contentDocumentService.findByCourse(course.getId());
        List<ContentSection> sections = contentDocumentService.listSections(document.getId());
        List<ContentKnowledgePoint> points = contentDocumentService.listKnowledgePoints(document.getId());
        Map<String, Object> update = new HashMap<>();
        update.put("document", document);
        update.put("sections", sections);
        update.put("points", points);
        return update;
    }

    /** L1 内容设计评审节点（前置门禁）：REVISE 时记修订次数并携带不满意见供重生成参考 */
    private Map<String, Object> reviewL1Node(OverAllState state, Course course, UserProfile profile,
                                             StageListener listener) throws Exception {
        listener.onStage("REVIEWING", "正在评审内容结构（内容设计评审）…");
        ContentDocument document = state.<ContentDocument>value("document").orElseThrow();
        List<ContentSection> sections = state.<List<ContentSection>>value("sections").orElseThrow();
        List<ContentKnowledgePoint> points = state.<List<ContentKnowledgePoint>>value("points").orElseThrow();
        Verdict verdict = contentReviewService.reviewContentDesign(document, sections, points, profile);

        Map<String, Object> update = new HashMap<>();
        update.put("l1Verdict", verdict.getVerdict());
        update.put("l1Revised", verdict.isRevise() ? 1 : 0);
        // REVISE 时把结构问题反馈给下一轮理解（问题随重生成自然消解；理解 prompt 不直接消费，仅记录用于汇总）
        String summary = "内容设计评审: " + verdict.getVerdict()
                + (verdict.getScore() != null ? "（" + verdict.getScore() + " 分）" : "")
                + (verdict.getIssues().isEmpty() ? "" : "，问题: " + String.join("；", verdict.getIssues()));
        update.put("reviewSummary", summary);
        log.info("L1 内容设计评审: courseId={}, verdict={}, issues={}", course.getId(), verdict.getVerdict(), verdict.getIssues());
        return update;
    }

    /** 渲染节点：从 ContentDocument 渲染笔记（带 L2 反馈的定向重生成），笔记全文入 state 供 L2 评审 */
    private Map<String, Object> renderNode(OverAllState state, Course course,
                                           List<CourseTranscriptSegment> transcript, List<CourseFrame> frames,
                                           int durationSec, StageListener listener) throws Exception {
        listener.onStage("NOTE_GENERATING", "正在生成 AI 笔记…");
        ContentDocument document = state.<ContentDocument>value("document").orElseThrow();
        List<ContentSection> sections = state.<List<ContentSection>>value("sections").orElseThrow();
        List<ContentKnowledgePoint> points = state.<List<ContentKnowledgePoint>>value("points").orElseThrow();
        List<String> feedback = state.<List<String>>value("feedback").orElse(List.of());

        NoteGenerationResult result = noteGenerationService.generateAndSaveNoteFromDocument(
                course, document, sections, points, transcript, frames, durationSec, feedback);
        Map<String, Object> update = new HashMap<>();
        update.put("noteMarkdown", result.getContent());
        if (!result.getQualityDefects().isEmpty()) {
            update.put("noteIssue", "AI 笔记质检未通过（" + String.join("；", result.getQualityDefects()) + "），已降级入库");
        }
        return update;
    }

    /** L2 双评审节点：讲解 / 练习两角色并行评审（courseExecutor），汇总意见 */
    private Map<String, Object> reviewL2Node(OverAllState state, Course course, UserProfile profile,
                                             StageListener listener) throws Exception {
        listener.onStage("REVIEWING", "正在多角色评审笔记（讲解 / 练习并行）…");
        String markdown = state.<String>value("noteMarkdown")
                .orElseThrow(() -> new IllegalStateException("渲染节点未产出笔记"));

        Future<Verdict> explainFuture = courseExecutor.submit(() ->
                contentReviewService.reviewNote(Role.EXPLAIN, markdown, profile));
        Verdict practiceVerdict = contentReviewService.reviewNote(Role.PRACTICE, markdown, profile);
        Verdict explainVerdict = explainFuture.get();

        List<String> issues = new ArrayList<>();
        for (Verdict verdict : List.of(explainVerdict, practiceVerdict)) {
            if (verdict.isRevise()) {
                issues.addAll(verdict.getIssues());
            }
        }

        Map<String, Object> update = new HashMap<>();
        int rounds = state.<Integer>value("l2Rounds").orElse(0);
        update.put("l2Rounds", issues.isEmpty() ? rounds : rounds + 1);
        update.put("feedback", issues);
        String summary = state.<String>value("reviewSummary").orElse("") + "；讲解评审: "
                + explainVerdict.getVerdict() + "；练习评审: " + practiceVerdict.getVerdict();
        if (!issues.isEmpty()) {
            summary += "（评审意见: " + String.join("；", issues) + "）";
        }
        update.put("reviewSummary", summary);
        log.info("L2 双评审: courseId={}, 讲解={}, 练习={}, 汇总问题={}",
                course.getId(), explainVerdict.getVerdict(), practiceVerdict.getVerdict(), issues.size());
        return update;
    }

    /** 写题 Agent 节点：按命题规划逐题实现（判题反馈轮带不合格清单重写） */
    private Map<String, Object> writeQuizNode(OverAllState state, Course course,
                                              List<CourseTranscriptSegment> transcript, UserProfile profile,
                                              StageListener listener) throws Exception {
        listener.onStage("QUIZ_GENERATING", "写题 Agent 正在按规划编写课后习题…");
        List<PlanItem> plan = state.<List<PlanItem>>value("quizPlan").orElse(List.of());
        Map<String, Object> update = new HashMap<>();
        if (plan.isEmpty()) {
            // 规划已失败：跳过出题（noteIssue 已由规划节点附加）
            update.put("quizDraft", List.of());
            return update;
        }
        try {
            List<String> judgeFeedback = state.<List<String>>value("judgeFeedback").orElse(List.of());
            List<QuizQuestion> draft = quizPlanService.writeQuiz(plan, transcript, profile, judgeFeedback);
            update.put("quizDraft", draft);
        } catch (Exception e) {
            log.warn("写题 Agent 失败, courseId={}", course.getId(), e);
            update.put("quizDraft", List.of());
            appendIssue(update, "课后习题生成失败：写题异常（" + e.getMessage() + "）");
        }
        return update;
    }

    /**
     * 判题 Agent 节点：代码闸（QuizQualityChecker）→ LLM 评审（reviewQuiz 结合画像）；
     * 不合格且还有重写轮次 → 反馈回写题；通过（或轮次耗尽且有产出）→ 落库
     */
    private Map<String, Object> judgeQuizNode(OverAllState state, Course course,
                                              List<CourseTranscriptSegment> transcript, int durationSec,
                                              UserProfile profile, StageListener listener) throws Exception {
        listener.onStage("QUIZ_REVIEWING", "判题 Agent 正在评审课后习题…");
        List<PlanItem> plan = state.<List<PlanItem>>value("quizPlan").orElse(List.of());
        List<QuizQuestion> draft = state.<List<QuizQuestion>>value("quizDraft").orElse(List.of());
        Map<String, Object> update = new HashMap<>();
        if (draft.isEmpty()) {
            return update;
        }

        // 代码闸
        QuizQualityChecker.Result checked = QuizQualityChecker.check(draft, transcript, List.of(), durationSec, List.of());
        List<QuizQuestion> kept = new ArrayList<>(checked.getKept());
        List<String> problems = new ArrayList<>(checked.getRemovedReasons());

        // 判题 LLM 评审
        Verdict verdict = null;
        if (!kept.isEmpty()) {
            verdict = contentReviewService.reviewQuiz(quizPlanService.questionsText(kept), profile);
            if (verdict.isRevise()) {
                problems.addAll(verdict.getIssues());
            }
        }

        int rounds = state.<Integer>value("quizRounds").orElse(0);
        boolean bad = kept.isEmpty() || (verdict != null && verdict.isRevise());
        if (bad && rounds < 1) {
            update.put("quizRounds", 1);
            update.put("judgeFeedback", judgeFeedback(plan, problems, verdict));
            log.info("判题 Agent 未通过，反馈回写题, courseId={}, kept={}, problems={}",
                    course.getId(), kept.size(), problems.size());
            return update;
        }

        if (kept.isEmpty()) {
            appendIssue(update, "课后习题生成失败：重写后仍无合格题目");
            return update;
        }
        courseQuizService.saveQuizQuestions(course, kept);
        update.put("quizRounds", rounds);
        if (verdict != null && verdict.isRevise() && !verdict.getIssues().isEmpty()) {
            appendIssue(update, "课后习题评审意见（已按现状入库）：" + String.join("；", verdict.getIssues()));
        }
        log.info("判题 Agent 通过, courseId={}, 题数={}, 评审={}",
                course.getId(), kept.size(), verdict == null ? "（代码闸后无题可评）" : verdict.getVerdict());
        return update;
    }

    /** 判题反馈构造：按规划条目列出未覆盖 / 不合格项（写题 Agent 按此补写） */
    static List<String> judgeFeedback(List<PlanItem> plan, List<String> problems, Verdict verdict) {
        List<String> feedback = new ArrayList<>();
        if (!problems.isEmpty()) {
            feedback.addAll(problems);
        }
        if (verdict != null) {
            feedback.addAll(verdict.getIssues());
        }
        feedback.add("请严格按命题规划逐条实现（共 " + plan.size() + " 条），每条规划对应一道题，不得遗漏或自行增减");
        return feedback;
    }

    private static void appendIssue(Map<String, Object> update, String issue) {
        // noteIssue 采用「覆盖写」：节点只附加自己的问题，已有问题由调用方视角在 state 中轮转保留
        // 这里读取不可行（节点只拿 update），因此 writeQuiz/judgeQuiz 的失败问题以独立键由最后汇总合并
        update.put("quizIssue", issue);
    }
}
