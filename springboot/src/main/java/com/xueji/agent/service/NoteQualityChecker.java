package com.xueji.agent.service;

import com.xueji.agent.domain.entity.CourseTranscriptSegment;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * AI 笔记产物质量校验（B26 阶段 1，零成本第一道闸）：
 * 代码级检查生成笔记的结构与可信性——四段标题齐全、章节时间线时间戳合法、知识点非空、
 * 正文与转写字数比合理。纯函数便于单测；不合格由调用方带缺陷清单重生成（详见 B26）。
 * 网课流水线与 B11 对话转写共用。
 */
public final class NoteQualityChecker {

    /** COURSE_TRANSCRIPT_PROMPT 约定的四个二级标题 */
    private static final String[] REQUIRED_SECTIONS = {"课程概览", "章节时间线", "知识点", "总结"};

    /** 章节时间线时间戳引用：[mm:ss] 或 [h:mm:ss] */
    private static final Pattern TIMESTAMP = Pattern.compile("\\[(\\d{1,2}:[0-5]\\d(?::[0-5]\\d)?)\\]");

    /** 正文与转写的最小字数比（低于判定为偷工减料） */
    private static final double MIN_RATIO = 0.1;

    /** 正文最小绝对字数 */
    private static final int MIN_NOTE_CHARS = 200;

    private NoteQualityChecker() {
    }

    /**
     * 校验生成的笔记，返回缺陷清单（空 = 通过）
     *
     * @param noteMarkdown  LLM 生成的笔记全文
     * @param transcript    原始转写分段（用于时间戳范围与字数比）
     * @param durationSec   视频时长（秒）
     */
    public static List<String> check(String noteMarkdown, List<CourseTranscriptSegment> transcript, int durationSec) {
        List<String> defects = new ArrayList<>();
        if (noteMarkdown == null || noteMarkdown.isBlank()) {
            defects.add("笔记内容为空");
            return defects;
        }

        // 1. 四段标题齐全
        for (String section : REQUIRED_SECTIONS) {
            if (!noteMarkdown.contains("## " + section)) {
                defects.add("缺少「" + section + "」章节");
            }
        }
        if (!defects.isEmpty()) {
            // 结构残缺时后续检查意义不大，直接返回结构性缺陷
            return defects;
        }

        // 2. 章节时间线的时间戳必须存在且在视频时长范围内
        String timeline = extractSection(noteMarkdown, "章节时间线");
        if (timeline == null || timeline.isBlank()) {
            defects.add("章节时间线一节内容为空");
        } else {
            Matcher matcher = TIMESTAMP.matcher(timeline);
            int count = 0;
            while (matcher.find()) {
                count++;
                int sec = parseTs(matcher.group(1));
                if (durationSec > 0 && sec > durationSec + 60) {
                    defects.add("章节时间线时间戳 [" + matcher.group(1) + "] 超出视频时长");
                }
            }
            if (count == 0 && transcript != null && !transcript.isEmpty()) {
                defects.add("章节时间线缺少时间戳");
            }
        }

        // 3. 知识点非空
        String knowledge = extractSection(noteMarkdown, "知识点");
        if (knowledge == null || knowledge.replace("###", "").replace("#", "").isBlank()) {
            defects.add("知识点一节内容为空");
        }

        // 4. 正文与转写字数比
        int noteChars = noteMarkdown.length();
        int transcriptChars = 0;
        if (transcript != null) {
            for (CourseTranscriptSegment segment : transcript) {
                transcriptChars += segment.getText() == null ? 0 : segment.getText().length();
            }
        }
        if (noteChars < MIN_NOTE_CHARS) {
            defects.add("正文过短（" + noteChars + " 字），疑似生成不完整");
        } else if (transcriptChars >= 200 && noteChars < transcriptChars * MIN_RATIO) {
            defects.add(String.format("正文仅 %d 字，相对转写 %d 字明显过短，疑似大量遗漏", noteChars, transcriptChars));
        }
        return defects;
    }

    /** 提取指定二级标题到下一个同级标题之间的内容（不含标题行） */
    static String extractSection(String markdown, String sectionTitle) {
        String[] lines = markdown.split("\n");
        boolean in = false;
        StringBuilder sb = new StringBuilder();
        for (String line : lines) {
            if (line.startsWith("## ")) {
                if (in) {
                    break;
                }
                in = line.startsWith("## " + sectionTitle);
                continue;
            }
            if (in) {
                sb.append(line).append('\n');
            }
        }
        return sb.toString();
    }

    /** mm:ss / h:mm:ss → 秒（非法返回 -1） */
    static int parseTs(String ts) {
        String[] parts = ts.split(":");
        try {
            int sec = 0;
            for (String part : parts) {
                sec = sec * 60 + Integer.parseInt(part);
            }
            return sec;
        } catch (NumberFormatException e) {
            return -1;
        }
    }
}
