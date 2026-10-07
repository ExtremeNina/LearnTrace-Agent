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
import com.xueji.agent.domain.entity.ContentDocument;
import com.xueji.agent.domain.entity.ContentKnowledgePoint;
import com.xueji.agent.domain.entity.ContentSection;
import com.xueji.agent.domain.entity.Course;
import com.xueji.agent.domain.entity.CourseFrame;
import com.xueji.agent.domain.entity.CourseTranscriptSegment;
import com.xueji.agent.domain.entity.UserProfile;
import com.xueji.agent.service.ContentDocumentService;
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
 * 理解 - 评审 - 渲染子流程 Graph 化（B26 阶段 3，SAA StateGraph 驱动）：
 *
 * <pre>
 * START → understand → reviewL1 ─(PASS / 修订耗尽)→ render → reviewL2 ─(PASS / 修订耗尽)→ END
 *              ↑            └─(REVISE 首次)─┘              └─(REVISE 首次)─┘（带反馈重渲染）
 * </pre>
 *
 * 两级评审时序：L1 内容设计评审为前置门禁（不过则重生成理解，≤1 次）；
 * L2 讲解 / 练习双评审在单节点内经线程池并行（三评审角色 × 维度矩阵驱动）。
 * 媒体前置链路（上传 / 抽取 / ASR / OCR）仍由 CoursePipelineService 编排，不在本图内
 */
@Slf4j
@Component
public class NotePipelineGraphRunner {

    /** 阶段进度回调（由 CoursePipelineService 桥接到 COURSE 事件推送与课程 stage 持久化） */
    public interface StageListener {
        void onStage(String stage, String text);
    }

    /** 执行结果：笔记问题（质检缺陷 + 未通过的评审意见汇总），NULL = 全部通过 */
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
                        Map.entry("reviewSummary", new ReplaceStrategy())));

        StateGraph graph = new StateGraph(factory)
                .addNode("understand", AsyncNodeAction.node_async((NodeAction) state ->
                        understandNode(course, transcript, durationSec, listener)))
                .addNode("reviewL1", AsyncNodeAction.node_async((NodeAction) state ->
                        reviewL1Node(state, course, profile, listener)))
                .addNode("render", AsyncNodeAction.node_async((NodeAction) state ->
                        renderNode(state, course, transcript, frames, durationSec, listener)))
                .addNode("reviewL2", AsyncNodeAction.node_async((NodeAction) state ->
                        reviewL2Node(state, course, profile, listener)))
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
                    return !feedback.isEmpty() && canRevise ? "render" : END;
                }), Map.of("render", "render", END, END));

        CompiledGraph compiled = graph.compile();
        OverAllState finalState = compiled.invoke(new HashMap<>())
                .orElseThrow(() -> new IllegalStateException("理解 - 评审 - 渲染子流程未产出状态"));

        RunResult result = new RunResult();
        result.noteIssue = finalState.<String>value("noteIssue").orElse(null);
        return result;
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

    /**
     * L2 双评审节点：讲解 / 练习两角色并行评审（courseExecutor），汇总意见
     */
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
}
