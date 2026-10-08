package com.xueji.agent.ai;

import cn.hutool.json.JSONArray;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.xueji.agent.ai.prompt.AgentPrompts;
import com.xueji.agent.ai.QuizAgentService.QuizQuestion;
import com.xueji.agent.domain.entity.CourseTranscriptSegment;
import com.xueji.agent.domain.entity.UserProfile;
import com.xueji.agent.exception.BusinessException;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * 出题 Agent（B27 三 Agent 协作重排：AI 笔记生成并评审通过后启动）：
 * 输入生成的 AI 笔记 + 学习画像，确认知识点数量、出题思路与题目编排。
 * 题量不设固定值（由笔记知识点覆盖决定，上限 100）；规划明显异常（<2 或 >100 条）时重规划一次，
 * 再失败抛异常由调用方兜底
 */
@Slf4j
@Service
public class QuizPlanService {

    /** 规划条数合理区间（异常时重规划一次） */
    static final int MIN_PLAN_ITEMS = 2;
    static final int MAX_PLAN_ITEMS = 100;

    /** 单条命题规划 */
    public static class PlanItem {
        private String knowledgePoint;
        private String questionType;
        private String angle;
        private Integer timeSec;

        public String getKnowledgePoint() {
            return knowledgePoint;
        }

        public String getQuestionType() {
            return questionType;
        }

        public String getAngle() {
            return angle;
        }

        public Integer getTimeSec() {
            return timeSec;
        }
    }

    @Resource
    private ChatClient generationChatClient;

    /**
     * 命题规划主入口（B27 重排：输入 AI 笔记 + 画像；重规划 ≤1 次）
     */
    public List<PlanItem> planQuiz(String noteMarkdown, UserProfile profile) {
        List<PlanItem> plan = planOnce(noteMarkdown, profile, null);
        if (plan.size() < MIN_PLAN_ITEMS || plan.size() > MAX_PLAN_ITEMS) {
            log.warn("命题规划数量异常（{} 条），重规划一次", plan.size());
            plan = planOnce(noteMarkdown, profile,
                    "上一版规划数量为 " + plan.size() + " 条，超出合理区间（" + MIN_PLAN_ITEMS + "~" + MAX_PLAN_ITEMS
                            + "），请按笔记知识点覆盖重新规划数量（每个知识点至少一题，上限 " + MAX_PLAN_ITEMS + "）");
        }
        if (plan.size() < MIN_PLAN_ITEMS || plan.size() > MAX_PLAN_ITEMS) {
            throw new BusinessException("命题规划异常，请稍后重试");
        }
        log.info("命题规划完成, 条数={}", plan.size());
        return plan;
    }

    private List<PlanItem> planOnce(String noteMarkdown, UserProfile profile, String feedback) {
        String text = generationChatClient.prompt()
                .system(AgentPrompts.QUIZ_PLAN_PROMPT)
                .user(buildPlanPrompt(noteMarkdown, profile, feedback))
                .call()
                .content();
        return parsePlan(text);
    }

    /** 规划用户消息（公开静态便于单测） */
    public static String buildPlanPrompt(String noteMarkdown, UserProfile profile, String feedback) {
        StringBuilder sb = new StringBuilder();
        sb.append("[AI 笔记全文]\n").append(noteMarkdown == null ? "" : noteMarkdown).append('\n');
        if (profile != null) {
            String profileText = ContentReviewService.profileText(profile);
            if (!profileText.contains("未提供")) {
                sb.append("\n[学习者画像]\n").append(profileText).append('\n');
            }
        }
        if (feedback != null && !feedback.isBlank()) {
            sb.append("\n[规划反馈] ").append(feedback).append('\n');
        }
        return sb.toString();
    }

    /**
     * 解析规划输出（容错：剥围栏、逐条跳过无知识点项；公开静态便于单测）
     */
    public static List<PlanItem> parsePlan(String text) {
        List<PlanItem> plan = new ArrayList<>();
        if (text == null || text.isBlank()) {
            return plan;
        }
        String cleaned = text.replace("```json", "").replace("```", "").trim();
        int start = cleaned.indexOf('{');
        int end = cleaned.lastIndexOf('}');
        if (start < 0 || end <= start) {
            return plan;
        }
        JSONObject obj = JSONUtil.parseObj(cleaned.substring(start, end + 1));
        JSONArray array = obj.getJSONArray("plan");
        if (array == null) {
            return plan;
        }
        for (Object item : array) {
            try {
                JSONObject o = (JSONObject) item;
                String knowledgePoint = o.getStr("knowledgePoint", null);
                if (knowledgePoint == null || knowledgePoint.isBlank()) {
                    continue;
                }
                PlanItem item1 = new PlanItem();
                item1.knowledgePoint = knowledgePoint.trim();
                item1.questionType = o.getStr("questionType", "概念理解");
                item1.angle = o.getStr("angle", "");
                item1.timeSec = o.getInt("timeSec", null);
                if (item1.timeSec != null && item1.timeSec < 0) {
                    item1.timeSec = null;
                }
                plan.add(item1);
            } catch (Exception e) {
                // 单项解析失败跳过
            }
        }
        return plan;
    }

    /** 写题样本上限 */
    private static final int WRITE_TRANSCRIPT_CHARS = 12000;

    /**
     * 写题 Agent（三 Agent 协作第二步）：按命题规划逐题实现（单次 LLM 调用输出全部题）。
     * judgeFeedback 非空时为判题反馈轮（按不合格清单重写）
     */
    public List<QuizQuestion> writeQuiz(List<PlanItem> plan, List<CourseTranscriptSegment> transcript,
                                        UserProfile profile, List<String> judgeFeedback) {
        String text = generationChatClient.prompt()
                .system(AgentPrompts.QUIZ_WRITE_PROMPT)
                .user(buildWritePrompt(plan, transcript, profile, judgeFeedback))
                .call()
                .content();
        List<QuizQuestion> questions = QuizAgentService.parseQuestions(text);
        if (questions.isEmpty()) {
            throw new BusinessException("写题结果为空，请稍后重试");
        }
        return questions;
    }

    /** 写题用户消息（公开静态便于单测） */
    public static String buildWritePrompt(List<PlanItem> plan, List<CourseTranscriptSegment> transcript,
                                          UserProfile profile, List<String> judgeFeedback) {
        StringBuilder sb = new StringBuilder();
        sb.append("[命题规划（一条对应一道题，严格按此实现）]\n");
        for (int i = 0; i < plan.size(); i++) {
            PlanItem item = plan.get(i);
            sb.append(i + 1).append(". 知识点：").append(item.getKnowledgePoint())
                    .append("｜题型：").append(item.getQuestionType())
                    .append("｜角度：").append(item.getAngle());
            if (item.getTimeSec() != null) {
                sb.append("｜时间点：").append(item.getTimeSec()).append("s");
            }
            sb.append('\n');
        }
        sb.append("\n[转写原文（修正版优先，含起止秒）]\n");
        int chars = 0;
        for (CourseTranscriptSegment segment : transcript) {
            String line = segment.getTextCorrected() != null ? segment.getTextCorrected() : segment.getText();
            if (line == null || line.isBlank()) {
                continue;
            }
            sb.append("[").append(segment.getStartSec()).append("-").append(segment.getEndSec()).append("s] ")
                    .append(line).append('\n');
            chars += line.length();
            if (chars >= WRITE_TRANSCRIPT_CHARS) {
                sb.append("…（后文截断）\n");
                break;
            }
        }
        if (profile != null) {
            String profileText = ContentReviewService.profileText(profile);
            if (!profileText.contains("未提供")) {
                sb.append("\n[学习者画像]\n").append(profileText).append('\n');
            }
        }
        if (judgeFeedback != null && !judgeFeedback.isEmpty()) {
            sb.append("\n[判题反馈] 上一版题目存在以下问题，请按规划重新输出全部题目：\n");
            for (String item : judgeFeedback) {
                sb.append("- ").append(item).append('\n');
            }
        }
        return sb.toString();
    }

    /** 题目集 → 判题用文本（与 QuizAgentService.questionsText 同形） */
    public String questionsText(List<QuizQuestion> questions) {
        return QuizAgentService.questionsText(questions);
    }
}
