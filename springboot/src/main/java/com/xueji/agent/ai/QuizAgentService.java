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
import com.xueji.agent.mapper.ContentKnowledgePointMapper;
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
 * QuizAgent（B26 阶段 4 子代理委派，agent-as-tool）：
 * 基于内部资料（ContentDocument 知识点 + 转写样本）出练习题，每题标注依据时间戳；
 * 产物经阶段 3 练习评审角色（可解性 / 难度分布 / 依据标注）评审，REVISE 带意见重出 ≤1 次；
 * 题目落 question_record（subject = 课程学科），复用 B14 练习抽题与复习队列。
 * 单次 LLM 调用（秒级~十几秒），不满足 B11 异步门槛，同步执行
 */
@Slf4j
@Service
public class QuizAgentService {

    private static final int TRANSCRIPT_EXCERPT_CHARS = 3000;

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
    private ContentKnowledgePointMapper knowledgePointMapper;

    @Resource
    private CourseTranscriptSegmentMapper transcriptMapper;

    @Resource
    private QuestionRecordMapper questionRecordMapper;

    /**
     * 出题主入口（工具调用）：
     *
     * @return 给 LLM 的结果文本（含题目预览与去向提示）
     */
    public String generate(Long userId, String courseHint, Integer count) {
        Course course = resolveCourse(userId, courseHint);
        int n = count == null || count < 1 ? 5 : Math.min(count, 10);
        UserProfile profile = profileService.getByUser(userId);

        ContentDocument document = contentDocumentService.findByCourse(course.getId());
        List<ContentSection> sections = document == null ? List.of()
                : contentDocumentService.listSections(document.getId());
        List<ContentKnowledgePoint> points = document == null ? List.of()
                : contentDocumentService.listKnowledgePoints(document.getId());
        String transcriptExcerpt = loadTranscriptExcerpt(course.getId());

        List<QuizQuestion> questions = askQuiz(course, document, sections, points, transcriptExcerpt, n, profile, List.of());
        Verdict verdict = contentReviewService.reviewQuiz(questionsText(questions), profile);
        if (verdict.isRevise()) {
            log.info("QuizAgent 产物评审未通过，带意见重出一次, courseId={}, issues={}", course.getId(), verdict.getIssues());
            questions = askQuiz(course, document, sections, points, transcriptExcerpt, n, profile, verdict.getIssues());
        }

        persist(userId, course, questions);
        log.info("QuizAgent 出题完成, courseId={}, count={}", course.getId(), questions.size());
        return formatForChat(course, questions);
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

    /** QuizAgent 一次出题调用（reviewIssues 非空时为带评审反馈的重出） */
    private List<QuizQuestion> askQuiz(Course course, ContentDocument document, List<ContentSection> sections,
                                       List<ContentKnowledgePoint> points, String transcriptExcerpt, int count,
                                       UserProfile profile, List<String> reviewIssues) {
        String text = generationChatClient.prompt()
                .system(AgentPrompts.QUIZ_AGENT_PROMPT)
                .user(buildQuizUserPrompt(course, document, sections, points, transcriptExcerpt, count, profile, reviewIssues))
                .call()
                .content();
        List<QuizQuestion> questions = parseQuestions(text);
        if (questions.isEmpty()) {
            throw new BusinessException("出题结果为空，请稍后重试");
        }
        return questions;
    }

    /** QuizAgent 用户消息（公开静态便于单测） */
    public static String buildQuizUserPrompt(Course course, ContentDocument document, List<ContentSection> sections,
                                             List<ContentKnowledgePoint> points, String transcriptExcerpt,
                                             int count, UserProfile profile, List<String> reviewIssues) {
        StringBuilder sb = new StringBuilder();
        sb.append("课程：《").append(course.getTitle()).append("》\n");
        if (document != null && document.getSummary() != null && !document.getSummary().isBlank()) {
            sb.append("摘要：").append(document.getSummary()).append('\n');
        }
        if (!sections.isEmpty()) {
            sb.append("\n[章节]\n");
            for (ContentSection section : sections) {
                sb.append("- ").append(section.getTitle()).append(" [")
                        .append(section.getStartSec()).append("-").append(section.getEndSec()).append("s]\n");
            }
        }
        if (!points.isEmpty()) {
            sb.append("\n[知识点]\n");
            for (ContentKnowledgePoint point : points) {
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
        if (transcriptExcerpt != null && !transcriptExcerpt.isBlank()) {
            sb.append("\n[转写原文样本]\n").append(transcriptExcerpt).append('\n');
        }
        sb.append("\n请出 ").append(count).append(" 道练习题。\n");
        if (profile != null) {
            String profileText = ContentReviewService.profileText(profile);
            if (!profileText.contains("未提供")) {
                sb.append("学习者画像：").append(profileText).append('\n');
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

    /** 落库 question_record（subject = 课程学科；依据时间戳入 analysis 前缀） */
    private void persist(Long userId, Course course, List<QuizQuestion> questions) {
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

    /** 转写样本（修正版优先，截断到上限） */
    private String loadTranscriptExcerpt(Long courseId) {
        List<CourseTranscriptSegment> segments = transcriptMapper.selectList(
                new QueryWrapper<CourseTranscriptSegment>()
                        .eq("course_id", courseId)
                        .orderByAsc("sort"));
        StringBuilder sb = new StringBuilder();
        for (CourseTranscriptSegment segment : segments) {
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
}
