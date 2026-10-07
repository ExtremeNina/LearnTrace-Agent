package com.xueji.agent.ai;

import cn.hutool.json.JSONArray;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.xueji.agent.ai.ContentReviewService.Verdict;
import com.xueji.agent.ai.prompt.AgentPrompts;
import com.xueji.agent.domain.entity.ContentDocument;
import com.xueji.agent.domain.entity.ContentKnowledgePoint;
import com.xueji.agent.domain.entity.ContentSection;
import com.xueji.agent.domain.entity.Course;
import com.xueji.agent.domain.entity.CourseTranscriptSegment;
import com.xueji.agent.domain.entity.QuestionRecord;
import com.xueji.agent.domain.entity.UserProfile;
import com.xueji.agent.exception.BusinessException;
import com.xueji.agent.mapper.ContentSectionMapper;
import com.xueji.agent.mapper.CourseMapper;
import com.xueji.agent.mapper.CourseTranscriptSegmentMapper;
import com.xueji.agent.mapper.QuestionRecordMapper;
import com.xueji.agent.service.ContentDocumentService;
import com.xueji.agent.service.ProfileService;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * QuizAgent（B26 阶段 4 + 习题产物化）：
 * 基于内部资料（ContentDocument 知识点 + 转写样本）出练习题，数量自适应（时长 / 知识点 / 难度 / 画像）。
 * 三明治质检：出题 → QuizQualityChecker 代码闸（幻觉 / 依据 / 重复，坏题剔除 + 不足补出）→
 * 判题 Agent（ContentReviewService.reviewQuiz 结合画像）REVISE 重出 ≤1（重出后再过代码闸）。
 * 本类只负责 AI 出题核心（无课程习题落库）；课程习题落库在 CourseQuizService，对话委派落 question_record
 */
@Slf4j
@Service
public class QuizAgentService {

    /** 转写样本上限：自适应数量规划需看全貌，DeepSeek 上下文充足（代码闸幻觉检测始终用全量） */
    private static final int TRANSCRIPT_EXCERPT_CHARS = 12000;

    /** 自适应数量下限（代码闸：Agent 规划结果不足该数时补出） */
    static final int MIN_ADAPTIVE_COUNT = 3;

    /** 单题结构（LLM 输出解析后的中间模型） */
    public static class QuizQuestion {
        private String question;
        private String answer;
        private String analysis;
        private Integer sourceSec;

        public String getQuestion() {
            return question;
        }

        public String getAnswer() {
            return answer;
        }

        public String getAnalysis() {
            return analysis;
        }

        public Integer getSourceSec() {
            return sourceSec;
        }
    }

    @Resource
    private ChatClient generationChatClient;

    @Resource
    private ContentReviewService contentReviewService;

    @Resource
    private ContentDocumentService contentDocumentService;

    @Resource
    private ProfileService profileService;

    @Resource
    private CourseMapper courseMapper;

    @Resource
    private ContentSectionMapper sectionMapper;

    @Resource
    private CourseTranscriptSegmentMapper transcriptMapper;

    @Resource
    private QuestionRecordMapper questionRecordMapper;

    /**
     * 对话委派入口（generate_practice 工具）：定位课程 → produceQuestions（三明治质检）→ 落 question_record
     *
     * @return 给 LLM 的结果文本（含题目预览与去向提示）
     */
    public String generate(Long userId, String courseHint, Integer count) {
        Course course = resolveCourse(userId, courseHint);
        int n = count == null || count < 1 ? 5 : Math.min(count, 15);
        UserProfile profile = profileService.getByUser(userId);

        QuizMaterial material = loadMaterial(course);
        List<QuizQuestion> questions = produceQuestions(course, material, profile, n, List.of());

        for (QuizQuestion q : questions) {
            String analysis = q.getSourceSec() != null
                    ? "（依据 " + ContentSearchService.fmt(q.getSourceSec()) + " 转写）" + (q.getAnalysis() == null ? "" : q.getAnalysis())
                    : q.getAnalysis();
            questionRecordMapper.insert(new QuestionRecord()
                    .setUserId(userId)
                    .setQuestionText(q.getQuestion())
                    .setCorrectAnswer(q.getAnswer())
                    .setAnalysis(analysis)
                    .setSubject(course.getSubject())
                    .setAiStatus("SUCCESS")
                    .setRecordStatus("SAVED")
                    .setDeleted(0)
                    .setCreatedAt(LocalDateTime.now())
                    .setUpdatedAt(LocalDateTime.now()));
        }
        log.info("QuizAgent 出题完成（对话委派）, courseId={}, count={}", course.getId(), questions.size());
        return formatForChat(course, questions);
    }

    /**
     * 出题核心（无落库，课程习题与对话委派共用）：
     * 出题 → 代码闸（剔除 + 不足补出）→ 判题 Agent →（REVISE 重出 → 代码闸）
     *
     * @param count    目标数量；NULL = 自适应（Agent 按时长/知识点/难度/画像规划，下限 MIN_ADAPTIVE_COUNT）
     * @param existing 已有题面（追加出题防重复）
     */
    public List<QuizQuestion> produceQuestions(Course course, QuizMaterial material, UserProfile profile,
                                               Integer count, List<String> existing) {
        int durationSec = material.durationSec();
        List<QuizQuestion> questions = askQuiz(course, material, profile, count, existing, List.of());
        QuizQualityChecker.Result checked = QuizQualityChecker.check(
                questions, material.transcript(), material.knowledgePoints(), durationSec, existing);
        List<QuizQuestion> kept = new ArrayList<>(checked.getKept());

        // 代码闸剔除后数量不足：带缺陷补出一次（只补缺口，与已保留题查重）
        boolean adaptive = count == null;
        int shortfall = adaptive
                ? Math.max(0, MIN_ADAPTIVE_COUNT - kept.size())
                : Math.max(0, count - kept.size());
        List<String> defects = new ArrayList<>(checked.getRemovedReasons());
        if (shortfall > 0 && !defects.isEmpty()) {
            List<String> currentTexts = new ArrayList<>(existing);
            for (QuizQuestion q : kept) {
                currentTexts.add(q.getQuestion());
            }
            List<QuizQuestion> supplement = askQuiz(course, material, profile, shortfall, currentTexts, defects);
            QuizQualityChecker.Result rechecked = QuizQualityChecker.check(
                    supplement, material.transcript(), material.knowledgePoints(), durationSec, currentTexts);
            kept.addAll(rechecked.getKept());
            defects.addAll(rechecked.getRemovedReasons());
        }
        if (!defects.isEmpty()) {
            log.info("课后习题代码闸剔除/补出, courseId={}, 保留={}, 剔除={}", course.getId(), kept.size(), defects.size());
        }

        // 判题 Agent（结合画像整体评审），REVISE 重出 ≤1 次
        Verdict verdict = contentReviewService.reviewQuiz(questionsText(kept), profile);
        if (verdict.isRevise()) {
            log.info("判题 Agent 未通过，带意见重出一次, courseId={}, issues={}", course.getId(), verdict.getIssues());
            List<String> currentTexts = new ArrayList<>(existing);
            for (QuizQuestion q : kept) {
                currentTexts.add(q.getQuestion());
            }
            List<QuizQuestion> revised = askQuiz(course, material, profile, count == null ? kept.size() : count,
                    currentTexts, verdict.getIssues());
            QuizQualityChecker.Result rechecked = QuizQualityChecker.check(
                    revised, material.transcript(), material.knowledgePoints(), durationSec, existing);
            if (!rechecked.getKept().isEmpty()) {
                kept = new ArrayList<>(rechecked.getKept());
            }
        }
        return kept;
    }

    /** QuizAgent 一次出题调用（reviewIssues 非空时为带判题反馈的重出；count NULL = 自适应） */
    private List<QuizQuestion> askQuiz(Course course, QuizMaterial material, UserProfile profile,
                                       Integer count, List<String> existing, List<String> reviewIssues) {
        String text = generationChatClient.prompt()
                .system(AgentPrompts.QUIZ_AGENT_PROMPT)
                .user(buildQuizUserPrompt(course, material, count, profile, existing, reviewIssues))
                .call()
                .content();
        List<QuizQuestion> questions = parseQuestions(text);
        if (questions.isEmpty()) {
            throw new BusinessException("出题结果为空，请稍后重试");
        }
        return questions;
    }

    /** 出题材料（课程 + 内容文档 + 转写），CourseQuizService 组装一次传给 produceQuestions */
    public record QuizMaterial(ContentDocument document, List<ContentSection> sections,
                               List<ContentKnowledgePoint> knowledgePoints,
                               List<CourseTranscriptSegment> transcript, int durationSec) {
    }

    /** 组装出题材料：文档/章节/知识点 + 转写全文（修正版优先） */
    public QuizMaterial loadMaterial(Course course) {
        ContentDocument document = contentDocumentService.findByCourse(course.getId());
        List<ContentSection> sections = document == null ? List.of()
                : contentDocumentService.listSections(document.getId());
        List<ContentKnowledgePoint> points = document == null ? List.of()
                : contentDocumentService.listKnowledgePoints(document.getId());
        List<CourseTranscriptSegment> transcript = transcriptMapper.selectList(
                new QueryWrapper<CourseTranscriptSegment>()
                        .eq("course_id", course.getId())
                        .orderByAsc("sort"));
        int durationSec = course.getDuration() == null ? 0 : course.getDuration();
        if (durationSec == 0) {
            for (CourseTranscriptSegment segment : transcript) {
                if (segment.getEndSec() != null && segment.getEndSec() > durationSec) {
                    durationSec = segment.getEndSec();
                }
            }
        }
        return new QuizMaterial(document, sections, points, transcript, durationSec);
    }

    /** 课程定位：hint 空 → 用户最近的完成课程；否则按标题模糊匹配 */
    Course resolveCourse(Long userId, String courseHint) {
        QueryWrapper<Course> wrapper = new QueryWrapper<Course>()
                .eq("user_id", userId)
                .eq("status", "SUCCESS")
                .orderByDesc("created_at")
                .last("LIMIT 1");
        if (courseHint != null && !courseHint.isBlank()) {
            wrapper = new QueryWrapper<Course>()
                    .eq("user_id", userId)
                    .eq("status", "SUCCESS")
                    .like("title", courseHint.trim())
                    .orderByDesc("created_at")
                    .last("LIMIT 1");
        }
        Course course = courseMapper.selectOne(wrapper);
        if (course == null) {
            throw new BusinessException(courseHint == null || courseHint.isBlank()
                    ? "还没有处理完成的网课，先在学习台上传或转写一个视频"
                    : "没找到名为「" + courseHint + "」的已处理完成网课，可以说出更准确的课程名");
        }
        return course;
    }

    /** QuizAgent 用户消息（公开静态便于单测）；count NULL = 自适应，existing 非空时提示避免重复 */
    public static String buildQuizUserPrompt(Course course, QuizMaterial material, Integer count,
                                             UserProfile profile, List<String> existing, List<String> reviewIssues) {
        StringBuilder sb = new StringBuilder();
        sb.append("课程：《").append(course.getTitle()).append("》\n");
        sb.append("视频时长：").append(material.durationSec()).append(" 秒。\n");
        if (material.document() != null && material.document().getSummary() != null
                && !material.document().getSummary().isBlank()) {
            sb.append("摘要：").append(material.document().getSummary()).append('\n');
        }
        if (!material.sections().isEmpty()) {
            sb.append("\n[章节]\n");
            for (ContentSection section : material.sections()) {
                sb.append("- ").append(section.getTitle()).append(" [")
                        .append(section.getStartSec()).append("-").append(section.getEndSec()).append("s]\n");
            }
        }
        if (!material.knowledgePoints().isEmpty()) {
            sb.append("\n[知识点]\n");
            for (ContentKnowledgePoint point : material.knowledgePoints()) {
                sb.append("- ").append(point.getName());
                if (point.getTimeSec() != null) {
                    sb.append(" [").append(point.getTimeSec()).append("s]");
                }
                if (point.getDetail() != null && !point.getDetail().isBlank()) {
                    sb.append("：").append(point.getDetail());
                }
                sb.append('\n');
            }
        }
        sb.append("\n[转写原文样本]\n").append(transcriptExcerpt(material.transcript())).append('\n');
        sb.append('\n');
        if (count == null) {
            sb.append("请按数量规划指引自主决定题目数量并输出 count。\n");
        } else {
            sb.append("请出 ").append(count).append(" 道练习题。\n");
        }
        if (profile != null) {
            String profileText = ContentReviewService.profileText(profile);
            if (!profileText.contains("未提供")) {
                sb.append("学习者画像：").append(profileText).append('\n');
            }
        }
        if (existing != null && !existing.isEmpty()) {
            sb.append("\n[已有题目（新题必须避免重复，请换知识点或角度）]\n");
            for (String existingQuestion : existing) {
                sb.append("- ").append(existingQuestion).append('\n');
            }
        }
        if (reviewIssues != null && !reviewIssues.isEmpty()) {
            sb.append("\n[评审反馈] 上一版题目经评审存在以下问题，请修正后重新输出全部题目：\n");
            for (String issue : reviewIssues) {
                sb.append("- ").append(issue).append('\n');
            }
        }
        return sb.toString();
    }

    /** 转写样本（修正版优先，截断到上限） */
    static String transcriptExcerpt(List<CourseTranscriptSegment> transcript) {
        StringBuilder sb = new StringBuilder();
        for (CourseTranscriptSegment segment : transcript) {
            String text = segment.getTextCorrected() != null ? segment.getTextCorrected() : segment.getText();
            if (text == null || text.isBlank()) {
                continue;
            }
            sb.append(ContentSearchService.fmt(segment.getStartSec())).append(" ").append(text).append('\n');
            if (sb.length() >= TRANSCRIPT_EXCERPT_CHARS) {
                break;
            }
        }
        return sb.length() > TRANSCRIPT_EXCERPT_CHARS ? sb.substring(0, TRANSCRIPT_EXCERPT_CHARS) + "…" : sb.toString();
    }

    /**
     * 解析 QuizAgent 输出（容错：剥围栏、兼容对象 / 数组两种形态、逐条跳过无题面项；公开静态便于单测）
     */
    public static List<QuizQuestion> parseQuestions(String text) {
        List<QuizQuestion> questions = new ArrayList<>();
        if (text == null || text.isBlank()) {
            return questions;
        }
        String cleaned = text.replace("```json", "").replace("```", "").trim();
        JSONArray array;
        int objStart = cleaned.indexOf('{');
        int arrStart = cleaned.indexOf('[');
        if (arrStart >= 0 && (objStart < 0 || arrStart < objStart)) {
            array = JSONUtil.parseArray(cleaned.substring(arrStart, cleaned.lastIndexOf(']') + 1));
        } else if (objStart >= 0) {
            JSONObject obj = JSONUtil.parseObj(cleaned.substring(objStart, cleaned.lastIndexOf('}') + 1));
            array = obj.getJSONArray("questions");
        } else {
            return questions;
        }
        if (array == null) {
            return questions;
        }
        for (Object item : array) {
            try {
                JSONObject o = (JSONObject) item;
                String question = o.getStr("question", null);
                if (question == null || question.isBlank()) {
                    continue;
                }
                QuizQuestion q = new QuizQuestion();
                q.question = question.trim();
                q.answer = o.getStr("answer", "");
                q.analysis = o.getStr("analysis", "");
                q.sourceSec = o.getInt("sourceSec", null);
                if (q.sourceSec != null && q.sourceSec < 0) {
                    q.sourceSec = null;
                }
                questions.add(q);
            } catch (Exception e) {
                // 单项解析失败跳过
            }
        }
        return questions;
    }

    /** 题目集 → 评审用文本 */
    static String questionsText(List<QuizQuestion> questions) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < questions.size(); i++) {
            QuizQuestion q = questions.get(i);
            sb.append(i + 1).append(". ").append(q.getQuestion()).append('\n');
            sb.append("   答案：").append(q.getAnswer()).append('\n');
            if (q.getSourceSec() != null) {
                sb.append("   依据：[").append(q.getSourceSec()).append("s]\n");
            }
        }
        return sb.toString();
    }

    /** 对话展示文本（题目预览 + 去向提示；公开静态便于单测） */
    public static String formatForChat(Course course, List<QuizQuestion> questions) {
        StringBuilder sb = new StringBuilder();
        sb.append("已基于《").append(course.getTitle()).append("》生成 ").append(questions.size())
                .append(" 道练习题：\n\n");
        for (int i = 0; i < questions.size(); i++) {
            QuizQuestion q = questions.get(i);
            sb.append(i + 1).append(". ").append(q.getQuestion());
            if (q.getSourceSec() != null) {
                sb.append(" ").append(ContentSearchService.fmt(q.getSourceSec()));
            }
            sb.append('\n');
        }
        sb.append("\n题目已存入题目管理，可去「练习测验」抽题练习；需要参考答案可以让我逐题讲解。");
        return sb.toString();
    }
}
