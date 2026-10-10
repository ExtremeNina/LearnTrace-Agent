package com.xueji.agent.ai;

import com.xueji.agent.domain.entity.CourseTranscriptSegment;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 笔记时间戳校准器（方案 C 生成后确定性回填）：
 * LLM 渲染正文时缺乏时间锚定，倾向输出占位 [00:00]（视频未开播就出现知识点时间戳）。
 * 本类在 normalizeTimestamps 之后、入库之前运行，用句级转写做 char-bigram 词面匹配，
 * 把每个 [mm:ss] 回填为其锚定文本在转写中实际出现的 startSec：
 * <ul>
 *   <li>命中 → 回填（含单调钳制：正文顺序≈视频顺序，回填时间不回退）</li>
 *   <li>未命中且原值为 00:00 → 移除胶囊（降级纯文本，宁缺勿错）</li>
 *   <li>未命中且非 0 → 保留原值（可能来自章节切片的真实时间）</li>
 * </ul>
 * 纯静态无状态，便于单测；转写为空或正文无时间戳时原样返回，不阻断生成主流程。
 */
public final class NoteTimestampCalibrator {

    /** [mm:ss] / [h:mm:ss]（normalizeTimestamps 的产物格式） */
    private static final Pattern TS = Pattern.compile("\\[(\\d{1,3}:[0-5]\\d(?::[0-5]\\d)?)\\]");

    /** 命中阈值：锚定文本的 gram 被候选转写句覆盖的比例下限 */
    private static final double HIT_THRESHOLD = 0.35;

    /** 锚定文本 gram 数下限：太短（如单个小节标题）不可靠，不参与判定 */
    private static final int MIN_GRAMS = 4;

    /** 锚定文本取时间戳前至多这么多字符（一个时间戳锚定的是紧邻其前的那个分句/分句组） */
    private static final int ANCHOR_MAX_CHARS = 100;

    /** 每个时间戳的候选句搜索窗口：从上一命中句位置向前搜索这么多句（正文顺序≈转写顺序，滑窗控复杂度） */
    private static final int SEARCH_WINDOW = 300;

    /** 命中后允许回退的句数（容纳笔记少量乱序引用） */
    private static final int BACKTRACK = 20;

    /**
     * 候选句文本长度上限：超过视为伪句级粗粒度残留（如 ASR 整分片打包的 ~900 字大段）。
     * 巨型分段的 bigram 集合对任何锚定文本覆盖度都高、无区分度，参与匹配只会误命中；
     * 全部被过滤时走「无佐证」降级（00:00 移除、非 0 保留），杜绝误回填+单调钳制连坐
     */
    private static final int MAX_CANDIDATE_CHARS = 300;

    private NoteTimestampCalibrator() {
    }

    /** 单个转写句的索引：起始秒 + 词面 gram 集合 */
    private record Sentence(int startSec, Set<String> grams) {
    }

    private record Hit(int index, double score) {
    }

    /**
     * 校准笔记正文中的时间戳。
     *
     * @param markdown   normalizeTimestamps 之后的笔记全文
     * @param transcript 句级转写分段（textCorrected 优先，与生成输入同源）
     */
    public static String calibrate(String markdown, List<CourseTranscriptSegment> transcript) {
        if (markdown == null || markdown.isBlank() || transcript == null || transcript.isEmpty()) {
            return markdown;
        }
        List<Sentence> sentences = index(transcript);
        // 候选为空（含粗粒度大段全被过滤）时继续走匹配流程：bestMatch 恒返回 null → 全部按无佐证降级，
        // 而非原样返回——否则粗粒度数据下会保留成片占位 00:00

        Matcher matcher = TS.matcher(markdown);
        StringBuilder sb = new StringBuilder();
        int cursor = 0;   // 滑窗起点：上一个命中句下标
        int prevSec = 0;  // 单调约束：上一个回填时间
        int lastEnd = 0;  // 上一个时间戳结束位置（锚定文本的最早起点）
        while (matcher.find()) {
            int originalSec = parseTs(matcher.group(1));
            String anchor = anchorText(markdown, lastEnd, matcher.start());
            lastEnd = matcher.end();

            Hit hit = bestMatch(sentences, anchor, cursor);
            String replacement;
            if (hit != null) {
                int sec = Math.max(sentences.get(hit.index()).startSec(), prevSec);
                prevSec = sec;
                cursor = hit.index();
                replacement = fmtTs(sec);
            } else if (originalSec == 0) {
                replacement = ""; // 占位 00:00 且无转写佐证：移除胶囊
            } else {
                replacement = matcher.group(); // 非 0 无佐证：保留原值
            }
            matcher.appendReplacement(sb, Matcher.quoteReplacement(replacement));
        }
        matcher.appendTail(sb);
        return sb.toString();
    }

    /** 构建句级索引：textCorrected 优先，按 startSec 升序；超长粗粒度大段（伪句级残留）不参与匹配 */
    private static List<Sentence> index(List<CourseTranscriptSegment> transcript) {
        List<Sentence> sentences = new ArrayList<>();
        for (CourseTranscriptSegment segment : transcript) {
            String text = segment.getTextCorrected() != null ? segment.getTextCorrected() : segment.getText();
            if (text == null || text.isBlank() || segment.getStartSec() == null) {
                continue;
            }
            if (text.length() > MAX_CANDIDATE_CHARS) {
                continue;
            }
            Set<String> grams = QuizQualityChecker.grams(text);
            if (!grams.isEmpty()) {
                sentences.add(new Sentence(segment.getStartSec(), grams));
            }
        }
        sentences.sort(Comparator.comparingInt(Sentence::startSec));
        return sentences;
    }

    /** 锚定文本：时间戳紧邻其前的文本（段落起点/上一个时间戳之后起，最多 ANCHOR_MAX_CHARS） */
    private static String anchorText(String markdown, int lastEnd, int tsStart) {
        int start = lastEnd;
        for (int i = tsStart - 1; i >= lastEnd; i--) {
            if (markdown.charAt(i) == '\n') {
                start = i + 1;
                break;
            }
        }
        if (tsStart - start > ANCHOR_MAX_CHARS) {
            start = tsStart - ANCHOR_MAX_CHARS;
        }
        return markdown.substring(start, tsStart);
    }

    /**
     * 在滑窗内找锚定文本覆盖度最高的转写句。
     * 两段式：先单句匹配（精度优先）；无单句命中再尝试「句 i + 句 i+1」合并候选
     * （容纳锚定分句跨转写句的情形，合并候选覆盖面宽，仅在无更准候选时兜底）
     */
    private static Hit bestMatch(List<Sentence> sentences, String anchor, int cursor) {
        Set<String> anchorGrams = QuizQualityChecker.grams(anchor);
        if (anchorGrams.size() < MIN_GRAMS) {
            return null;
        }
        int from = Math.max(0, cursor - BACKTRACK);
        int to = Math.min(sentences.size(), cursor + SEARCH_WINDOW);
        Hit best = null;
        for (int i = from; i < to; i++) {
            double score = coverage(anchorGrams, sentences.get(i).grams());
            if (score >= HIT_THRESHOLD && (best == null || score > best.score())) {
                best = new Hit(i, score);
            }
        }
        if (best != null) {
            return best;
        }
        for (int i = from; i < to - 1; i++) {
            Set<String> merged = new java.util.HashSet<>(sentences.get(i).grams());
            merged.addAll(sentences.get(i + 1).grams());
            double score = coverage(anchorGrams, merged);
            if (score >= HIT_THRESHOLD && (best == null || score > best.score())) {
                best = new Hit(i, score);
            }
        }
        return best;
    }

    /** 覆盖率：锚定 gram 中被候选句覆盖的比例 */
    private static double coverage(Set<String> anchorGrams, Set<String> candidateGrams) {
        int hits = 0;
        for (String gram : anchorGrams) {
            if (candidateGrams.contains(gram)) {
                hits++;
            }
        }
        return (double) hits / anchorGrams.size();
    }

    /** mm:ss / h:mm:ss → 秒（非法返回 -1） */
    private static int parseTs(String ts) {
        try {
            int sec = 0;
            for (String part : ts.split(":")) {
                sec = sec * 60 + Integer.parseInt(part);
            }
            return sec;
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    /** 秒 → [mm:ss]（与 NoteGenerationService.fmtTs 同格式，分钟可超 59，前端胶囊正则兼容） */
    private static String fmtTs(long sec) {
        return String.format("[%02d:%02d]", sec / 60, sec % 60);
    }
}
