package com.xueji.agent.service;

import com.xueji.agent.domain.entity.CourseTranscriptSegment;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * AI 笔记产物质量校验单测（B26 阶段 1）：结构完整 / 章节页非空 / 时间戳合法 / 字数比。
 * 时间戳方案 B 结构（每章一个 ## 标题，行尾 [mm:ss]）为主，旧四段结构保留兼容用例
 */
class NoteQualityCheckerTest {

    private CourseTranscriptSegment segment(int start, int end, String text) {
        return new CourseTranscriptSegment().setStartSec(start).setEndSec(end).setText(text).setSort(0);
    }

    /** 方案 B 结构：概览 + 代码拼接的时间线表 + 每章一页（标题行尾带 [mm:ss]）+ 总结 */
    private String validNote() {
        return """
                # 测试课程 — 课程笔记

                ## 课程概览
                **一句话概括：** 测试视频内容概述。

                ## 章节时间线
                | 章节 | 起止时间 |
                | --- | --- |
                | 一、开场 | [00:00] - [02:00] |
                | 二、深入 | [02:00] - [09:00] |

                ## 一、开场 [00:00]
                ### 反照率效应 [00:30]
                冰面反射阳光，温度越低反射越强，形成正反馈。

                ## 二、深入 [02:00]
                ### 洋流效应 [02:30]
                洋流受风力与地转偏向力共同影响，形成全球热量输送带。

                ## 总结
                - 要点一
                - 要点二
                """;
    }

    /** 旧四段结构（## 知识点伞标题）：存量链路与遗留路径的产物，应继续通过校验 */
    private String legacyNote() {
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
    void legacyStructureShouldPass() {
        assertThat(NoteQualityChecker.check(legacyNote(), transcript(), 600)).isEmpty();
    }

    @Test
    void missingSectionShouldBeRejected() {
        String broken = validNote().replace("## 总结", "## 结语");
        List<String> defects = NoteQualityChecker.check(broken, transcript(), 600);
        assertThat(defects).anyMatch(d -> d.contains("总结"));
    }

    @Test
    void missingChapterPageShouldBeRejected() {
        String broken = validNote().replaceAll("(?s)## 一、开场.*?## 总结", "## 总结");
        List<String> defects = NoteQualityChecker.check(broken, transcript(), 600);
        assertThat(defects).anyMatch(d -> d.contains("章节内容页"));
    }

    @Test
    void outOfRangeTimestampShouldBeRejected() {
        String broken = validNote().replace("[09:00]", "[59:00]");
        List<String> defects = NoteQualityChecker.check(broken, transcript(), 600);
        assertThat(defects).anyMatch(d -> d.contains("超出视频时长"));
    }

    @Test
    void tooShortNoteShouldBeRejected() {
        String broken = validNote().replaceAll("(?s)## 一、开场.*?## 总结", "## 总结");
        List<String> defects = NoteQualityChecker.check(broken, transcript(), 600);
        assertThat(defects).anyMatch(d -> d.contains("过短"));
    }

    @Test
    void blankNoteShouldBeRejected() {
        List<String> defects = NoteQualityChecker.check("", transcript(), 600);
        assertThat(defects).anyMatch(d -> d.contains("内容为空"));
    }
}
