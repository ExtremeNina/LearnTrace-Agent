package com.xueji.agent.ai;

import com.xueji.agent.domain.entity.CourseTranscriptSegment;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 时间戳校准器：转写 bigram 匹配回填 / 00:00 降级 / 非 0 保留 / 单调钳制 / 空转写 no-op
 */
class NoteTimestampCalibratorTest {

    private CourseTranscriptSegment segment(int start, int end, String text) {
        return new CourseTranscriptSegment()
                .setCourseId(1L)
                .setStartSec(start)
                .setEndSec(end)
                .setText(text)
                .setSort(0);
    }

    @Test
    void hitShouldBackfillActualTranscriptTimeAndMissedZeroShouldBeDropped() {
        List<CourseTranscriptSegment> transcript = List.of(
                segment(0, 60, "大家好欢迎来到今天的课程"),
                segment(120, 180, "存储程序是冯诺依曼结构的核心思想"));
        String markdown = "# 计算机组成笔记\n\n## 课程概览\n\n本讲概览与学习目标说明 [00:00]\n\n"
                + "## 知识点\n\n冯诺依曼结构的核心思想是存储程序 [00:00]";

        String calibrated = NoteTimestampCalibrator.calibrate(markdown, transcript);

        // 命中：锚定文本与 120s 转写句词面高度重合 → 回填真实时间
        assertThat(calibrated).contains("冯诺依曼结构的核心思想是存储程序 [02:00]");
        // 未命中且占位 00:00 → 移除胶囊（纯文本保留）
        assertThat(calibrated).doesNotContain("[00:00]");
        assertThat(calibrated).contains("本讲概览与学习目标说明");
    }

    @Test
    void nonZeroWithoutEvidenceShouldBeKept() {
        List<CourseTranscriptSegment> transcript = List.of(
                segment(0, 60, "大家好欢迎来到今天的课程"));
        String markdown = "## 知识点\n\n这一节的讲义补充材料见 [05:20]，课后请自行查阅";

        String calibrated = NoteTimestampCalibrator.calibrate(markdown, transcript);

        // 非 0 且无佐证：保留原值（可能来自章节切片真实时间）
        assertThat(calibrated).contains("[05:20]");
    }

    @Test
    void backfillShouldNotGoBackwardsInTime() {
        // 笔记乱序引用：先写了后段内容（120s 总线），再引用前段内容（30s 存储程序）
        List<CourseTranscriptSegment> transcript = List.of(
                segment(30, 50, "存储程序是冯诺依曼结构的核心思想"),
                segment(120, 180, "今天讲解微机总线的三类信号"));
        String markdown = "## 知识点\n\n"
                + "微机总线的地址数据和控制三类信号 [01:00]\n\n"
                + "回顾：冯诺依曼结构的核心思想是存储程序 [00:30]";

        String calibrated = NoteTimestampCalibrator.calibrate(markdown, transcript);

        // 首个命中回填 120s；第二个虽然匹配到 30s 的句子，但单调钳制回填不早于 120s
        assertThat(calibrated).contains("微机总线的地址数据和控制三类信号 [02:00]");
        assertThat(calibrated).contains("冯诺依曼结构的核心思想是存储程序 [02:00]");
    }

    @Test
    void emptyTranscriptShouldBeNoOp() {
        String markdown = "正文 [00:00] 与 [03:00] 原样保留";
        assertThat(NoteTimestampCalibrator.calibrate(markdown, List.of())).isSameAs(markdown);
        assertThat(NoteTimestampCalibrator.calibrate(markdown, null)).isSameAs(markdown);
        assertThat(NoteTimestampCalibrator.calibrate(null, transcriptOf())).isNull();
    }

    private static List<CourseTranscriptSegment> transcriptOf() {
        return List.of(segment0());
    }

    private static CourseTranscriptSegment segment0() {
        return new CourseTranscriptSegment().setStartSec(0).setEndSec(10).setText("内容");
    }
}
