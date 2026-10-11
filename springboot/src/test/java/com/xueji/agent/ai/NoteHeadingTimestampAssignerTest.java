package com.xueji.agent.ai;

import com.xueji.agent.domain.entity.ContentKnowledgePoint;
import com.xueji.agent.domain.entity.ContentSection;
import com.xueji.agent.domain.entity.CourseTranscriptSegment;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 标题级时间戳赋值单测（时间戳方案 B）：
 * 章节标题取 section.startSec；知识点标题在所属章节窗口内 bigram 匹配、兜底 timeSec；
 * 正文违规 [mm:ss] 剥除（标题行与表格行除外）；全篇单调不回退
 */
class NoteHeadingTimestampAssignerTest {

    private ContentSection section(String title, int start, int end) {
        return new ContentSection().setTitle(title).setStartSec(start).setEndSec(end);
    }

    private ContentKnowledgePoint point(String name, int sectionSort, Integer timeSec) {
        return new ContentKnowledgePoint().setName(name).setSectionSort(sectionSort).setTimeSec(timeSec);
    }

    private CourseTranscriptSegment segment(int start, int end, String text) {
        return new CourseTranscriptSegment().setStartSec(start).setEndSec(end).setText(text).setSort(0);
    }

    @Test
    void sectionHeadingShouldTakeSectionStartSec() {
        String markdown = """
                # 测试课程 — 课程笔记

                ## 课程概览
                概述内容

                ## 章节时间线
                | 章节 | 起止时间 |
                | --- | --- |
                | 第一章 | [01:00] - [03:00] |

                ## 第一章

                ### 甲知识点
                正文一句。

                ## 总结
                - 要点
                """;
        String result = NoteTimestampCalibrator.assignHeadingTimestamps(
                markdown, List.of(section("第一章", 60, 180)), List.of(point("甲知识点", 0, 90)), null);

        assertThat(result).contains("## 第一章 [01:00]");
        assertThat(result).contains("### 甲知识点 [01:30]"); // 无转写 → 兜底 point.timeSec
        assertThat(result).contains("## 课程概览\n");
        assertThat(result).contains("## 章节时间线\n");
        assertThat(result).contains("## 总结\n"); // 结构性标题不加时间戳
        assertThat(result).contains("| 第一章 | [01:00] - [03:00] |"); // 表格行原样保留
    }

    @Test
    void knowledgePointHeadingShouldMatchTranscriptWithinSectionWindow() {
        List<CourseTranscriptSegment> transcript = List.of(
                segment(60, 75, "我们来看第一种题型 直接代入法 把数值直接代入表达式求极限"),
                segment(80, 95, "洛必达法则的核心是分子分母分别求导 注意不是对整个商求导"),
                segment(210, 225, "微分方程的定义与形式是本章的基础内容"));
        List<ContentSection> sections = List.of(section("第一章", 60, 200), section("第二章", 200, 400));
        List<ContentKnowledgePoint> points = List.of(
                point("直接代入法", 0, null), point("洛必达法则", 0, null), point("微分方程", 1, null));
        String markdown = """
                ## 第一章

                ### 直接代入法
                我们来看第一种题型，直接代入法，把数值直接代入表达式求极限。

                ### 洛必达法则
                洛必达法则的核心是分子分母分别求导，注意不是对整个商求导。

                ## 第二章

                ### 微分方程
                微分方程的定义与形式是本章的基础内容。
                """;

        String result = NoteTimestampCalibrator.assignHeadingTimestamps(markdown, sections, points, transcript);

        assertThat(result).contains("## 第一章 [01:00]");
        assertThat(result).contains("### 直接代入法 [01:00]");   // 命中第一章首句
        assertThat(result).contains("### 洛必达法则 [01:20]");   // 左端收缩后定位到实际讲解句，不前移到章节开头
        assertThat(result).contains("## 第二章 [03:20]");        // 章节标题 = section.startSec
        assertThat(result).contains("### 微分方程 [03:30]");     // 第二章首句
    }

    @Test
    void unmatchedAnchorShouldFallBackToKnowledgePointTime() {
        // 锚定段落与转写措辞完全不同 → 无命中 → 兜底 point.timeSec 钳进章节窗口
        List<CourseTranscriptSegment> transcript = List.of(
                segment(60, 75, "我们来看第一种题型 直接代入法 把数值直接代入表达式求极限"));
        String markdown = """
                ## 第一章

                ### 洛必达法则
                这一节讲解与转写措辞完全不同的另一套内容用于验证兜底逻辑。
                """;

        String result = NoteTimestampCalibrator.assignHeadingTimestamps(
                markdown, List.of(section("第一章", 60, 200)),
                List.of(point("洛必达法则", 0, 120)), transcript);

        assertThat(result).contains("### 洛必达法则 [02:00]");
    }

    @Test
    void noEvidenceAndNoTimeSecShouldLeaveHeadingBare() {
        String markdown = """
                ## 第一章

                ### 完全陌生的标题
                这一段既匹配不上转写也没有兜底时间。
                """;

        String result = NoteTimestampCalibrator.assignHeadingTimestamps(
                markdown, List.of(section("第一章", 60, 200)), List.of(point("别的名字", 0, null)), null);

        assertThat(result).contains("### 完全陌生的标题\n"); // 宁缺勿错，不加时间戳
    }

    @Test
    void bodyTimestampsShouldBeStrippedButHeadingsAndTableKept() {
        String markdown = """
                ## 课程概览
                概述提到 [02:49] 违规时间戳。

                ## 章节时间线
                | 章节 | 起止时间 |
                | --- | --- |
                | 第一章 | [01:00] - [03:00] |

                ## 第一章

                ### 甲知识点
                正文里也有 [03:25] 违规时间戳。
                """;

        String result = NoteTimestampCalibrator.assignHeadingTimestamps(
                markdown, List.of(section("第一章", 60, 180)), List.of(point("甲知识点", 0, 90)), null);

        assertThat(result).contains("概述提到 违规时间戳。");
        assertThat(result).contains("正文里也有 违规时间戳。");
        assertThat(result).contains("### 甲知识点 [01:30]");
        assertThat(result).contains("| 第一章 | [01:00] - [03:00] |");
        assertThat(result).doesNotContain("[02:49]").doesNotContain("[03:25]");
    }

    @Test
    void assignmentShouldBeMonotonic() {
        // 章节边界数据异常（第二章起点早于第一章已赋时间）：章节标题钳制不回退；知识点时间更早则弃用
        List<ContentSection> sections = List.of(section("第一章", 60, 200), section("第二章", 50, 300));
        String markdown = """
                ## 第一章

                ### 甲知识点
                内容。

                ## 第二章

                ### 晚知识点
                内容。
                """;
        String result = NoteTimestampCalibrator.assignHeadingTimestamps(
                markdown, sections, List.of(point("甲知识点", 0, 90), point("晚知识点", 1, 55)), null);

        assertThat(result).contains("## 第一章 [01:00]");
        assertThat(result).contains("### 甲知识点 [01:30]");
        assertThat(result).contains("## 第二章 [01:30]");   // max(50, prevSec=90)
        assertThat(result).contains("### 晚知识点\n");       // 55 < 90 违反单调 → 弃用
    }
}
