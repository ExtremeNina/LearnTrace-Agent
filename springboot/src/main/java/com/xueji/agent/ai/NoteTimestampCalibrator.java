package com.xueji.agent.ai;

import com.xueji.agent.domain.entity.ContentKnowledgePoint;
import com.xueji.agent.domain.entity.ContentSection;
import com.xueji.agent.domain.entity.CourseTranscriptSegment;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 笔记时间戳后处理（纯静态无状态，便于单测），两个入口：
 *
 * <ul>
 *   <li>{@link #assignHeadingTimestamps}（方案 B，现行）：LLM 渲染时不再输出任何时间戳，
 *       标题级 [mm:ss] 由代码从章节结构与句级转写确定性推导——## 章节标题取 section.startSec，
 *       ### 知识点标题用「标题+首段」做 bigram 匹配（限定所属章节秒窗口），正文违规时间戳剥除；</li>
 *   <li>{@link #calibrate}（方案 C，遗留路径专用）：回填 LLM 已输出的正文时间戳
 *       （LLM 渲染缺乏时间锚定、倾向输出占位 [00:00]），现行生产链路已不使用，保留供遗留路径与单测。</li>
 * </ul>
 * 两者均不阻断生成主流程：转写为空或正文无时间戳时原样/降级返回。
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

    /** 标题锚定文本上限（标题 + 小节首段，纯标题 gram 数常不足 MIN_GRAMS，需带一段正文增强区分度） */
    private static final int HEADING_ANCHOR_MAX_CHARS = 120;

    /** 标题匹配的合并句数上限（候选 = 从某句起连续合并若干句，实际由「gram 数≈锚文本量级」自适应截断） */
    private static final int HEADING_MERGE_SENTENCES = 12;

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

    /**
     * 标题级时间戳赋值（方案 B，normalizeTimestamps 之后、入库之前运行）。
     * 逐行扫描重写：
     * <ul>
     *   <li>## 章节标题行：按文档顺序与 sections 逐个对位（只前进，结构性标题/重名不会误配），
     *       时间 = section.startSec（边界已由理解层代码推导，无需词面匹配）</li>
     *   <li>### 知识点标题行：按名匹配知识点（精确 → 归一化），锚定文本 = 标题 + 小节首段，
     *       在所属章节秒窗口内做 bigram 覆盖度匹配；命中取实际 startSec，未命中兜底 point.timeSec
     *       （钳进章节窗口）；仍不可用则不加时间戳（宁缺勿错）</li>
     *   <li>其余正文行：剥除 LLM 违规输出的 [mm:ss]（表格行与标题行除外）</li>
     * </ul>
     * 全篇非递减单调（文档顺序≈视频顺序）：违反单调的候选直接弃用。
     */
    public static String assignHeadingTimestamps(String markdown, List<ContentSection> sections,
                                                 List<ContentKnowledgePoint> knowledgePoints,
                                                 List<CourseTranscriptSegment> transcript) {
        if (markdown == null || markdown.isBlank()) {
            return markdown;
        }
        List<Sentence> sentences = transcript == null || transcript.isEmpty() ? List.of() : index(transcript);
        String[] lines = markdown.split("\n", -1);
        StringBuilder sb = new StringBuilder();
        int prevSec = 0;       // 单调基线：上一个已赋时间戳
        int[] sectionCursor = new int[]{0}; // 章节标题对位指针（只前进）
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            String trimmed = line.trim();
            if (trimmed.startsWith("## ")) {
                String title = stripInlineTimestamps(trimmed.substring(3)).trim();
                ContentSection section = matchSectionForward(sections, sectionCursor, title);
                if (section != null && section.getStartSec() != null) {
                    int sec = Math.max(section.getStartSec(), prevSec);
                    prevSec = sec;
                    sb.append("## ").append(title).append(' ').append(fmtTs(sec)).append('\n');
                } else {
                    sb.append(line).append('\n');
                }
            } else if (trimmed.startsWith("### ")) {
                String name = stripInlineTimestamps(trimmed.substring(4)).trim();
                ContentKnowledgePoint point = matchPoint(knowledgePoints, sectionCursor[0] - 1, name);
                Integer sec = resolveHeadingSec(point, sections, headingAnchor(lines, i), sentences);
                if (sec != null && sec >= prevSec) {
                    prevSec = sec;
                    sb.append("### ").append(name).append(' ').append(fmtTs(sec)).append('\n');
                } else {
                    sb.append("### ").append(name).append('\n');
                }
            } else if (trimmed.startsWith("#") || trimmed.startsWith("|")) {
                sb.append(line).append('\n');
            } else {
                sb.append(stripInlineTimestamps(line)).append('\n');
            }
        }
        return sb.toString();
    }

    /** 章节标题对位：从指针位置向后找同名 section（跳过无知识点而未产出标题的章节），命中后指针推进到其后 */
    private static ContentSection matchSectionForward(List<ContentSection> sections, int[] cursor, String title) {
        if (sections == null) {
            return null;
        }
        for (int i = cursor[0]; i < sections.size(); i++) {
            ContentSection section = sections.get(i);
            if (section != null && title.equals(section.getTitle())) {
                cursor[0] = i + 1;
                return section;
            }
        }
        return null;
    }

    /** 知识点名对位：优先所属章节内精确匹配，其次归一化匹配（去空白与标点），最后全文兜底 */
    private static ContentKnowledgePoint matchPoint(List<ContentKnowledgePoint> points, int sectionIndex, String name) {
        if (points == null || points.isEmpty()) {
            return null;
        }
        String normalized = normalizeName(name);
        ContentKnowledgePoint global = null;
        ContentKnowledgePoint normalizedGlobal = null;
        for (ContentKnowledgePoint point : points) {
            boolean sameChapter = point.getSectionSort() != null && point.getSectionSort() == sectionIndex;
            if (name.equals(point.getName())) {
                if (sameChapter) {
                    return point;
                }
                if (global == null) {
                    global = point;
                }
            } else if (normalizeName(point.getName()).equals(normalized)) {
                if (sameChapter) {
                    return point;
                }
                if (normalizedGlobal == null) {
                    normalizedGlobal = point;
                }
            }
        }
        return global != null ? global : normalizedGlobal;
    }

    /** 知识点标题定秒：章节窗口内 bigram 匹配 → 兜底 point.timeSec → 钳进章节窗口 → 均不可用返回 null */
    private static Integer resolveHeadingSec(ContentKnowledgePoint point, List<ContentSection> sections,
                                             String anchor, List<Sentence> sentences) {
        if (point == null) {
            return null;
        }
        ContentSection section = sectionOf(point, sections);
        Integer sec = null;
        if (section != null && section.getStartSec() != null && section.getEndSec() != null && !sentences.isEmpty()) {
            Hit hit = bestMatchInRange(sentences, anchor, section.getStartSec(), section.getEndSec());
            if (hit != null) {
                sec = sentences.get(hit.index()).startSec();
            }
        }
        if (sec == null && point.getTimeSec() != null) {
            sec = point.getTimeSec();
        }
        if (sec == null) {
            return null;
        }
        if (section != null && section.getStartSec() != null && section.getEndSec() != null) {
            sec = Math.max(section.getStartSec(), Math.min(sec, section.getEndSec()));
        }
        return sec;
    }

    private static ContentSection sectionOf(ContentKnowledgePoint point, List<ContentSection> sections) {
        if (sections == null || point.getSectionSort() == null
                || point.getSectionSort() < 0 || point.getSectionSort() >= sections.size()) {
            return null;
        }
        return sections.get(point.getSectionSort());
    }

    /**
     * 在秒窗口内找锚定文本覆盖度最高的讲解起点：
     * 候选 = 从某句起连续合并若干句（合并 gram 数达到锚文本量级即停，保证候选与锚定文本体量相当），
     * 覆盖度达标后再做左端收缩——去掉左端句仍覆盖则推进起点，避免合并区间吞掉后续知识点内容导致定位前移
     */
    private static Hit bestMatchInRange(List<Sentence> sentences, String anchor, int windowStartSec, int windowEndSec) {
        Set<String> anchorGrams = QuizQualityChecker.grams(anchor);
        if (anchorGrams.size() < MIN_GRAMS) {
            return null;
        }
        for (int i = firstIndexAtOrAfter(sentences, windowStartSec); i < sentences.size(); i++) {
            if (sentences.get(i).startSec() > windowEndSec) {
                break;
            }
            Set<String> merged = new HashSet<>();
            int last = i - 1;
            for (int k = i; k < Math.min(sentences.size(), i + HEADING_MERGE_SENTENCES); k++) {
                if (sentences.get(k).startSec() > windowEndSec) {
                    break;
                }
                merged.addAll(sentences.get(k).grams());
                last = k;
                if (coverage(anchorGrams, merged) >= HIT_THRESHOLD) {
                    break;
                }
                if (merged.size() >= anchorGrams.size()) {
                    break; // 候选体量已超过锚文本仍不达标：该起点无望，提前止损
                }
            }
            if (coverage(anchorGrams, merged) >= HIT_THRESHOLD) {
                while (last > i
                        && coverage(anchorGrams, gramsOfRange(sentences, i + 1, last)) >= HIT_THRESHOLD) {
                    i++;
                }
                return new Hit(i, coverage(anchorGrams, merged));
            }
        }
        return null;
    }

    /** 合并 [from, to] 句区间的 gram 集合（左端收缩用） */
    private static Set<String> gramsOfRange(List<Sentence> sentences, int from, int to) {
        Set<String> grams = new HashSet<>();
        for (int k = from; k <= to; k++) {
            grams.addAll(sentences.get(k).grams());
        }
        return grams;
    }

    /** 二分定位：startSec ≥ windowStartSec 的首句下标（句索引已按 startSec 升序） */
    private static int firstIndexAtOrAfter(List<Sentence> sentences, int startSec) {
        int lo = 0;
        int hi = sentences.size();
        while (lo < hi) {
            int mid = (lo + hi) >>> 1;
            if (sentences.get(mid).startSec() < startSec) {
                lo = mid + 1;
            } else {
                hi = mid;
            }
        }
        return lo;
    }

    /** 标题锚定文本：标题本身 + 其后至下一个标题/表格行为止的正文（上限 HEADING_ANCHOR_MAX_CHARS） */
    private static String headingAnchor(String[] lines, int headingIdx) {
        StringBuilder sb = new StringBuilder(lines[headingIdx].trim());
        for (int i = headingIdx + 1; i < lines.length && sb.length() < HEADING_ANCHOR_MAX_CHARS; i++) {
            String trimmed = lines[i].trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            if (trimmed.startsWith("#") || trimmed.startsWith("|")) {
                break;
            }
            if (sb.length() + trimmed.length() > HEADING_ANCHOR_MAX_CHARS) {
                sb.append(' ').append(trimmed, 0, HEADING_ANCHOR_MAX_CHARS - sb.length());
                break;
            }
            sb.append(' ').append(trimmed);
        }
        return sb.toString();
    }

    /** 剥除行内时间戳胶囊（LLM 未遵守「不打点」禁令时的防御）并收敛多余空白 */
    private static String stripInlineTimestamps(String line) {
        return TS.matcher(line).replaceAll("").replaceAll("  +", " ");
    }

    /** 名称归一化：仅保留字母与数字（去空白/标点/全半角差异），供标题模糊对位 */
    private static String normalizeName(String name) {
        return name == null ? "" : name.replaceAll("[^\\p{L}\\p{N}]+", "");
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
