package com.xueji.agent.service;

import com.xueji.agent.domain.entity.CourseTranscriptSegment;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * AI 笔记产物质量校验单测（B26 阶段 1）：结构完整 / 时间戳合法 / 知识点非空 / 字数比
 */
class NoteQualityCheckerTest {

    private CourseTranscriptSegment segment(int start, int end, String text) {
        return new CourseTranscriptSegment().setStartSec(start).setEndSec(end).setText(text).setSort(0);
    }

    private String validNote() {
        return """
                # 测试课程 — 课程笔记

                ## 课程概览
                **一句话概括：** 测试视频内容概述。

                ## 章节时间线
                | 章节 | 起止时间 |
                | --- | --- |
                | 一、开场 | [00:00] – [02:00] |
                | 二、深入 | [02:00] – [09:00] |

                ## 知识点
                ### 反照率效应
                冰面反射阳光，温度越低反射越强，形成正反馈。

                ## 总结
                - 要点一
                - 要点二
                """;
    }

    private List<CourseTranscriptSegment> transcript() {
        List<CourseTranscriptSegment> list = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            list.add(segment(i * 60, (i + 1) * 60, "第" + i + "分钟的课程文本内容，讲解冰雪覆盖与洋流效应等知识点，语句较长以累计字数达到校验阈值。"));
        }
        return list;
    }

    @Test
    void validNoteShouldPass() {
        assertThat(NoteQualityChecker.check(validNote(), transcript(), 600)).isEmpty();
    }

    @Test
    void missingSectionShouldBeRejected() {
        String broken = validNote().replace("## 总结", "## 结语");
        List<String> defects = NoteQualityChecker.check(broken, transcript(), 600);
        assertThat(defects).anyMatch(d -> d.contains("总结"));
    }

    @Test
    void outOfRangeTimestampShouldBeRejected() {
        String broken = validNote().replace("[09:00]", "[59:00]");
        List<String> defects = NoteQualityChecker.check(broken, transcript(), 600);
        assertThat(defects).anyMatch(d -> d.contains("超出视频时长"));
    }

    @Test
    void emptyKnowledgeShouldBeRejected() {
        String broken = validNote().replaceAll("(?s)### 反照率效应.*?形成正反馈。", "");
        List<String> defects = NoteQualityChecker.check(broken, transcript(), 600);
        assertThat(defects).anyMatch(d -> d.contains("知识点"));
    }

    @Test
    void tooShortNoteShouldBeRejected() {
        String broken = validNote().replaceAll("(?s)## 知识点.*?## 总结", "## 知识点\n短\n\n## 总结");
        List<String> defects = NoteQualityChecker.check(broken, transcript(), 600);
        assertThat(defects).anyMatch(d -> d.contains("过短"));
    }

    @Test
    void blankNoteShouldBeRejected() {
        List<String> defects = NoteQualityChecker.check("", transcript(), 600);
        assertThat(defects).anyMatch(d -> d.contains("内容为空"));
    }
}
