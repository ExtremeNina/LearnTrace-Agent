package com.xueji.agent.ai;

import com.xueji.agent.ai.QuizAgentService.QuizQuestion;
import com.xueji.agent.domain.entity.ContentKnowledgePoint;
import com.xueji.agent.domain.entity.CourseTranscriptSegment;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 课后习题代码闸（三明治质检第一/三层，纯函数零成本）：
 * 结构完整 / 依据合法性（sourceSec 越界） / 幻觉检测（题面 bigram 在材料中的命中率，专拦零重叠硬幻觉） /
 * 重复检测（追加出题去重）。与笔记链路 NoteQualityChecker 对称：代码闸 → 判题 Agent → 代码闸。
 * 说明：中文无分词，词面法只能拦「几乎零重叠」的硬幻觉；软幻觉由判题 Agent（LLM）兜底
 */
public final class QuizQualityChecker {

    private QuizQualityChecker() {
    }

    /** 题面 bigram 在材料中的最低命中率（低于判硬幻觉剔除） */
    static final double MIN_COVER_RATIO = 0.2;

    /** 题面与已有题目的 Jaccard 相似度阈值（≥ 判重复） */
    static final double DUPLICATE_JACCARD = 0.6;

    /** 幻觉覆盖检测的最小题面长度（过短题面不做词面判定） */
    private static final int MIN_HALLUCINATION_CHECK_LEN = 10;

    /** 质检结果：保留的题目 + 被剔除原因清单 */
    public static class Result {
        private final List<QuizQuestion> kept = new ArrayList<>();
        private final List<String> removedReasons = new ArrayList<>();

        public List<QuizQuestion> getKept() {
            return kept;
        }

        public List<String> getRemovedReasons() {
            return removedReasons;
        }
    }

    /**
     * 代码闸：逐题检验，剔除坏题；existing 为追加出题场景的已有题面（判重复）
     */
    public static Result check(List<QuizQuestion> questions, List<CourseTranscriptSegment> transcript,
                               List<ContentKnowledgePoint> knowledgePoints, int durationSec,
                               List<String> existing) {
        Result result = new Result();
        String material = materialText(transcript, knowledgePoints);
        Set<String> materialGrams = grams(material);
        List<String> seenTexts = new ArrayList<>(existing == null ? List.of() : existing);

        if (questions != null) {
            for (QuizQuestion question : questions) {
                String reason = defectOf(question, materialGrams, durationSec, seenTexts);
                if (reason == null) {
                    result.kept.add(question);
                    seenTexts.add(question.getQuestion() == null ? "" : question.getQuestion());
                } else {
                    result.removedReasons.add(reason);
                }
            }
        }
        return result;
    }

    /** 单题缺陷判定（NULL = 通过） */
    static String defectOf(QuizQuestion question, Set<String> materialGrams, int durationSec,
                           List<String> seenTexts) {
        String text = question.getQuestion() == null ? "" : question.getQuestion().trim();
        if (text.isEmpty()) {
            return "题面为空";
        }
        if (question.getAnswer() == null || question.getAnswer().isBlank()) {
            return "缺参考答案：「" + truncate(text) + "」";
        }
        Integer sourceSec = question.getSourceSec();
        if (sourceSec != null && (sourceSec < 0 || (durationSec > 0 && sourceSec > durationSec))) {
            return "依据时间戳越界（" + sourceSec + "s）：「" + truncate(text) + "」";
        }
        if (text.length() >= MIN_HALLUCINATION_CHECK_LEN && coverRatio(text, materialGrams) < MIN_COVER_RATIO) {
            return "疑似脱离材料（与转写内容重叠过低）：「" + truncate(text) + "」";
        }
        if (isDuplicate(text, seenTexts)) {
            return "与已有题目重复：「" + truncate(text) + "」";
        }
        return null;
    }

    /**
     * 题面 bigram 在材料 bigram 集合中的命中率（连续字符块内滑窗，跨标点不连；英文块取整词）
     */
    static double coverRatio(String questionText, Set<String> materialGrams) {
        Set<String> questionGrams = grams(questionText);
        if (questionGrams.isEmpty()) {
            return 1.0;
        }
        int hit = 0;
        for (String gram : questionGrams) {
            if (materialGrams.contains(gram)) {
                hit++;
            }
        }
        return (double) hit / questionGrams.size();
    }

    /** 字符级 Jaccard 相似度判重复（题面 gram 集合） */
    static boolean isDuplicate(String text, List<String> seenTexts) {
        Set<String> a = grams(text);
        if (a.isEmpty()) {
            return false;
        }
        for (String seen : seenTexts) {
            Set<String> b = grams(seen);
            if (b.isEmpty()) {
                continue;
            }
            Set<String> union = new HashSet<>(a);
            union.addAll(b);
            Set<String> intersection = new HashSet<>(a);
            intersection.retainAll(b);
            if ((double) intersection.size() / union.size() >= DUPLICATE_JACCARD) {
                return true;
            }
        }
        return false;
    }

    /** 文本 → gram 集合：连续字母数字块内，中文 2 字滑窗、纯 ASCII 取整词小写 */
    static Set<String> grams(String text) {
        Set<String> grams = new HashSet<>();
        if (text == null) {
            return grams;
        }
        String normalized = Normalizer.normalize(text, Normalizer.Form.NFKC);
        StringBuilder block = new StringBuilder();
        for (int i = 0; i <= normalized.length(); i++) {
            char ch = i < normalized.length() ? normalized.charAt(i) : ' ';
            if (Character.isLetterOrDigit(ch)) {
                block.append(ch);
            } else {
                addBlockGrams(grams, block.toString());
                block.setLength(0);
            }
        }
        return grams;
    }

    private static void addBlockGrams(Set<String> grams, String block) {
        if (block.isEmpty()) {
            return;
        }
        boolean ascii = block.chars().allMatch(c -> c < 128);
        if (ascii) {
            if (block.length() >= 2) {
                grams.add(block.toLowerCase());
            }
            return;
        }
        for (int i = 0; i + 2 <= block.length(); i++) {
            grams.add(block.substring(i, i + 2));
        }
    }

    private static String materialText(List<CourseTranscriptSegment> transcript,
                                       List<ContentKnowledgePoint> knowledgePoints) {
        StringBuilder sb = new StringBuilder();
        if (transcript != null) {
            for (CourseTranscriptSegment segment : transcript) {
                String text = segment.getTextCorrected() != null ? segment.getTextCorrected() : segment.getText();
                if (text != null) {
                    sb.append(text).append('\n');
                }
            }
        }
        if (knowledgePoints != null) {
            for (ContentKnowledgePoint point : knowledgePoints) {
                if (point.getName() != null) {
                    sb.append(point.getName()).append(' ');
                }
                if (point.getDetail() != null) {
                    sb.append(point.getDetail()).append(' ');
                }
            }
        }
        return sb.toString();
    }

    private static String truncate(String text) {
        return text.length() <= 20 ? text : text.substring(0, 20) + "…";
    }
}
