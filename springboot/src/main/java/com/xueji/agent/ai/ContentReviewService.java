package com.xueji.agent.ai;

import cn.hutool.json.JSONArray;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.xueji.agent.ai.prompt.AgentPrompts;
import com.xueji.agent.domain.entity.ContentDocument;
import com.xueji.agent.domain.entity.ContentKnowledgePoint;
import com.xueji.agent.domain.entity.ContentSection;
import com.xueji.agent.domain.entity.UserProfile;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 多角色评审服务（B26 阶段 3）：三评审角色 × 维度矩阵驱动的 LLM 评审。
 * L1 内容设计评审（前置门禁，评审 ContentDocument）；L2 讲解 / 练习双评审（并行，渲染出的笔记）。
 * verdict JSON 容错解析；学习者画像未填写时按通用学习者评审
 */
@Slf4j
@Service
public class ContentReviewService {

    /** 评审角色（B26 三角色 × 维度矩阵） */
    public enum Role {
        /** L1 前置门禁：内容设计评审（评 ContentDocument） */
        CONTENT_DESIGN,
        /** L2 并行：讲解质量评审（评笔记） */
        EXPLAIN,
        /** L2 并行：练习适用性评审（评笔记，为阶段 4 出题铺垫） */
        PRACTICE
    }

    /** 评审结论 */
    public static class Verdict {
        private String verdict;
        private Integer score;
        private List<String> issues = new ArrayList<>();

        public boolean isRevise() {
            return "REVISE".equalsIgnoreCase(verdict);
        }

        public String getVerdict() {
            return verdict;
        }

        public Integer getScore() {
            return score;
        }

        public List<String> getIssues() {
            return issues;
        }
    }

    @Resource
    private ChatClient generationChatClient;

    /** 三评审角色 × 维度矩阵（B26 拍板：角色 × 关注维度；公开静态便于单测） */
    public static Map<Role, List<String>> dimensionMatrix() {
        Map<Role, List<String>> matrix = new LinkedHashMap<>();
        matrix.put(Role.CONTENT_DESIGN, List.of(
                "章节划分合理性（粒度与顺序符合内容逻辑）",
                "知识点覆盖完整性（重要内容无遗漏）",
                "时间戳真实性（章节起止与内容对应）",
                "主题聚焦度（不偏离材料主题）"));
        matrix.put(Role.EXPLAIN, List.of(
                "事实准确性（与转写原文一致，无编造）",
                "结构完整性（四段结构齐备）",
                "时间戳引用正确性（[mm:ss] 与原文对应）",
                "口语残留（无转写腔与元叙述）"));
        matrix.put(Role.PRACTICE, List.of(
                "可出题性（知识点可转化为练习题）",
                "难度适配（结合学习者画像）",
                "知识点可测性（表述清晰可判定对错）"));
        return matrix;
    }

    /** 维度矩阵 → 文本（拼入评审用户消息；公开静态便于单测） */
    public static String dimensionsText(Role role) {
        StringBuilder sb = new StringBuilder();
        for (String dimension : dimensionMatrix().get(role)) {
            sb.append("- ").append(dimension).append('\n');
        }
        return sb.toString();
    }

    /** 学习者画像 → 文本（未填写时按通用学习者） */
    public static String profileText(UserProfile profile) {
        if (profile == null) {
            return "（未提供学习者画像，按通用学习者评审）";
        }
        StringBuilder sb = new StringBuilder();
        if (profile.getGradeLevel() != null && !profile.getGradeLevel().isBlank()) {
            sb.append("学段：").append(profile.getGradeLevel()).append('；');
        }
        if (profile.getLevel() != null && !profile.getLevel().isBlank()) {
            sb.append("水平：").append(profile.getLevel()).append('；');
        }
        if (profile.getGoal() != null && !profile.getGoal().isBlank()) {
            sb.append("目标：").append(profile.getGoal()).append('；');
        }
        if (profile.getNote() != null && !profile.getNote().isBlank()) {
            sb.append("补充：").append(profile.getNote());
        }
        return sb.isEmpty() ? "（未提供学习者画像，按通用学习者评审）" : sb.toString();
    }

    /**
     * L1 内容设计评审用户消息：ContentDocument 结构摘要 + 画像 + 本角色维度
     */
    public static String buildContentReviewPrompt(ContentDocument document, List<ContentSection> sections,
                                                  List<ContentKnowledgePoint> points, UserProfile profile) {
        StringBuilder sb = new StringBuilder();
        sb.append("[评审对象] 内容设计文档（章节 + 知识点）\n");
        sb.append("标题：").append(document.getTitle()).append('\n');
        sb.append("摘要：").append(document.getSummary() == null ? "" : document.getSummary()).append("\n\n");
        sb.append("[章节]\n");
        for (ContentSection section : sections) {
            sb.append("- ").append(section.getTitle())
                    .append(" [").append(section.getStartSec()).append("-").append(section.getEndSec()).append("s]");
            if (section.getSummary() != null && !section.getSummary().isBlank()) {
                sb.append("：").append(section.getSummary());
            }
            sb.append('\n');
        }
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
        sb.append("\n[学习者画像]\n").append(profileText(profile));
        sb.append("\n\n[你的评审维度]\n").append(dimensionsText(Role.CONTENT_DESIGN));
        return sb.toString();
    }

    /**
     * L2 笔记评审用户消息：笔记全文 + 画像 + 本角色维度
     */
    public static String buildNoteReviewPrompt(Role role, String noteMarkdown, UserProfile profile) {
        StringBuilder sb = new StringBuilder();
        sb.append("[评审对象] AI 学习笔记（Markdown 全文）\n");
        sb.append(noteMarkdown).append("\n\n");
        sb.append("[学习者画像]\n").append(profileText(profile));
        sb.append("\n\n[你的评审维度]\n").append(dimensionsText(role));
        return sb.toString();
    }

    /** L1 内容设计评审 */
    public Verdict reviewContentDesign(ContentDocument document, List<ContentSection> sections,
                                       List<ContentKnowledgePoint> points, UserProfile profile) {
        return review(buildContentReviewPrompt(document, sections, points, profile));
    }

    /** L2 笔记评审（EXPLAIN / PRACTICE） */
    public Verdict reviewNote(Role role, String noteMarkdown, UserProfile profile) {
        return review(buildNoteReviewPrompt(role, noteMarkdown, profile));
    }

    private Verdict review(String userPrompt) {
        String text = generationChatClient.prompt()
                .system(AgentPrompts.CONTENT_REVIEW_PROMPT)
                .user(userPrompt)
                .call()
                .content();
        Verdict verdict = parseVerdict(text);
        log.info("内容评审完成: verdict={}, score={}, issues={}", verdict.getVerdict(), verdict.getScore(), verdict.getIssues());
        return verdict;
    }

    /**
     * 解析评审输出（容错：剥代码块围栏、取对象区间；verdict 非法值按 REVISE 保守处理）
     */
    public static Verdict parseVerdict(String text) {
        if (text == null || text.isBlank()) {
            throw new IllegalStateException("评审未返回内容");
        }
        String cleaned = text.replace("```json", "").replace("```", "").trim();
        int start = cleaned.indexOf('{');
        int end = cleaned.lastIndexOf('}');
        if (start < 0 || end <= start) {
            throw new IllegalStateException("评审输出不含 JSON 对象");
        }
        JSONObject obj = JSONUtil.parseObj(cleaned.substring(start, end + 1));
        Verdict verdict = new Verdict();
        String v = obj.getStr("verdict", "REVISE");
        verdict.verdict = "PASS".equalsIgnoreCase(v) ? "PASS" : "REVISE";
        verdict.score = obj.getInt("score", null);
        JSONArray issues = obj.getJSONArray("issues");
        if (issues != null) {
            for (Object item : issues) {
                String issue = String.valueOf(item);
                if (!issue.isBlank()) {
                    verdict.issues.add(issue);
                }
            }
        }
        return verdict;
    }
}
