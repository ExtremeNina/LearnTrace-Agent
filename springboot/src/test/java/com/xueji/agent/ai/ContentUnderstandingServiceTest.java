package com.xueji.agent.ai;

import com.xueji.agent.domain.entity.CourseTranscriptSegment;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 内容理解服务纯函数单测（B26 阶段 2）：语义分块、LLM JSON 容错解析、时间夹取
 */
class ContentUnderstandingServiceTest {

    private CourseTranscriptSegment segment(int start, int end, String text) {
        return new CourseTranscriptSegment().setStartSec(start).setEndSec(end).setText(text).setSort(0);
    }

    @Test
    void chunkSegmentsShouldSplitByCharBudget() {
        List<CourseTranscriptSegment> segments = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            segments.add(segment(i * 60, (i + 1) * 60, "字".repeat(1000)));
        }
        List<List<CourseTranscriptSegment>> chunks = ContentUnderstandingService.chunkSegments(segments, 4000);
        // 每块 ≤ 4000 字：4 段/块 → 3 块（4+4+2）
        assertThat(chunks).hasSize(3);
        for (List<CourseTranscriptSegment> chunk : chunks) {
            int chars = 0;
            for (CourseTranscriptSegment s : chunk) {
                chars += s.getText().length();
            }
            assertThat(chars).isLessThanOrEqualTo(4000);
        }
    }

    @Test
    void chunkSegmentsShouldKeepOversizeSegmentAlone() {
        List<CourseTranscriptSegment> segments = new ArrayList<>();
        segments.add(segment(0, 60, "长".repeat(9000)));
        segments.add(segment(60, 120, "短"));
        List<List<CourseTranscriptSegment>> chunks = ContentUnderstandingService.chunkSegments(segments, 4000);
        assertThat(chunks).hasSize(2);
        assertThat(chunks.get(0)).hasSize(1);
        assertThat(chunks.get(1)).hasSize(1);
    }

    @Test
    void parseShouldReadContentDocumentJson() {
        String text = """
                ```json
                {
                  "title": "如果全球冰封",
                  "summary": "科普视频摘要",
                  "sections": [
                    {"title": "冰封的原因", "summary": "反照率效应", "start": 0, "end": 200},
                    {"title": "洋流中断", "summary": "洋流停止", "start": 200, "end": 524}
                  ],
                  "knowledgePoints": [
                    {"name": "反照率效应", "detail": "冰面反射", "time": 252, "section": 1, "important": true, "errorProne": false},
                    {"name": "洋流中断", "detail": "停止循环", "time": 300, "section": 2},
                    {"name": "", "detail": "无效项"},
                    {"name": "越界时间", "time": 9999}
                  ]
                }
                ```
                """;
        ContentUnderstanding understanding = ContentUnderstandingService.parseUnderstanding(text, 524);
        assertThat(understanding.getTitle()).isEqualTo("如果全球冰封");
        assertThat(understanding.getSections()).hasSize(2);
        assertThat(understanding.getSections().get(1).getEndSec()).isEqualTo(524);
        // 空名项被跳过：①反照率 ②洋流中断 ③越界时间（时间夹取）= 3 条有效
        assertThat(understanding.getKnowledgePoints()).hasSize(3);
        assertThat(understanding.getKnowledgePoints().get(0).getTimeSec()).isEqualTo(252);
        assertThat(understanding.getKnowledgePoints().get(0).getSectionSort()).isZero();
        assertThat(understanding.getKnowledgePoints().get(1).getSectionSort()).isEqualTo(1);
        // 越界时间夹取到视频时长
        assertThat(understanding.getKnowledgePoints().get(2).getTimeSec()).isEqualTo(524);
        // 未声明 important 的默认 false
        assertThat(understanding.getKnowledgePoints().get(2).getImportant()).isFalse();
    }

    @Test
    void parseShouldSwapInvertedRangeAndSkipInvalidSections() {
        String text = """
                {"title": "t", "summary": "s",
                 "sections": [
                   {"title": "倒置", "start": 300, "end": 100},
                   {"title": "", "start": 0, "end": 10},
                   {"title": "负时间", "start": -5, "end": 10}
                 ], "knowledgePoints": []}
                """;
        ContentUnderstanding understanding = ContentUnderstandingService.parseUnderstanding(text, 600);
        // 倒置区间交换
        assertThat(understanding.getSections().get(0).getStartSec()).isEqualTo(100);
        assertThat(understanding.getSections().get(0).getEndSec()).isEqualTo(300);
        // 无标题 / 负时间章节跳过
        assertThat(understanding.getSections()).hasSize(1);
    }

    @Test
    void parseShouldThrowOnNonJson() {
        assertThatThrownBy(() -> ContentUnderstandingService.parseUnderstanding("抱歉我无法输出", 600))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> ContentUnderstandingService.parseUnderstanding(null, 600))
                .isInstanceOf(IllegalStateException.class);
    }

    // ---- 章节边界代码推导 + 均匀切分检测（时间戳锚定真实转写修复） ----

    private ContentUnderstanding.Section section(String title, Integer start, Integer end) {
        ContentUnderstanding.Section s = new ContentUnderstanding.Section();
        s.title = title;
        s.summary = "";
        s.startSec = start;
        s.endSec = end;
        return s;
    }

    private ContentUnderstanding.KnowledgePoint point(String name, Integer time, int sectionSort) {
        ContentUnderstanding.KnowledgePoint p = new ContentUnderstanding.KnowledgePoint();
        p.name = name;
        p.detail = "";
        p.timeSec = time;
        p.sectionSort = sectionSort;
        return p;
    }

    @Test
    void deriveShouldGroundSectionBoundariesOnPointTimes() {
        ContentUnderstanding understanding = new ContentUnderstanding();
        understanding.sections = new ArrayList<>(List.of(
                section("章节一", 0, 100),
                section("章节二", 100, 200),
                section("章节三", 200, 300)));
        understanding.knowledgePoints = new ArrayList<>(List.of(
                point("甲", 10, 0),
                point("乙", 130, 1),
                point("丙", 260, 2)));

        ContentUnderstandingService.deriveSectionBoundaries(understanding, 300);

        // 边界 = 归组内知识点最早时间，首切点 0、末切点时长，首尾衔接
        assertThat(understanding.sections.get(0).getStartSec()).isZero();
        assertThat(understanding.sections.get(0).getEndSec()).isEqualTo(130);
        assertThat(understanding.sections.get(1).getStartSec()).isEqualTo(130);
        assertThat(understanding.sections.get(1).getEndSec()).isEqualTo(260);
        assertThat(understanding.sections.get(2).getStartSec()).isEqualTo(260);
        assertThat(understanding.sections.get(2).getEndSec()).isEqualTo(300);
    }

    @Test
    void deriveShouldInterpolateUnanchoredSection() {
        ContentUnderstanding understanding = new ContentUnderstanding();
        understanding.sections = new ArrayList<>(List.of(
                section("章节一", 0, 100),
                section("章节二", 100, 200),
                section("章节三", 200, 300)));
        understanding.knowledgePoints = new ArrayList<>(List.of(
                point("甲", 50, 0),
                point("丙", 250, 2)));

        ContentUnderstandingService.deriveSectionBoundaries(understanding, 300);

        // 无锚点章节的切点按相邻已知切点线性插值：0 → 125 → 250 → 300
        assertThat(understanding.sections.get(0).getEndSec()).isEqualTo(125);
        assertThat(understanding.sections.get(1).getStartSec()).isEqualTo(125);
        assertThat(understanding.sections.get(1).getEndSec()).isEqualTo(250);
        assertThat(understanding.sections.get(2).getStartSec()).isEqualTo(250);
    }

    @Test
    void deriveShouldKeepLlmBoundariesWithoutAnchors() {
        ContentUnderstanding understanding = new ContentUnderstanding();
        understanding.sections = new ArrayList<>(List.of(section("章节一", 0, 150), section("章节二", 150, 300)));
        understanding.knowledgePoints = new ArrayList<>(List.of(point("无时间点", null, 0)));

        ContentUnderstandingService.deriveSectionBoundaries(understanding, 300);

        // 完全无锚点：保留 LLM 原边界（是否虚构由均匀切分告警提示）
        assertThat(understanding.sections.get(0).getStartSec()).isZero();
        assertThat(understanding.sections.get(1).getEndSec()).isEqualTo(300);
    }

    @Test
    void uniformSplitDetectionShouldFlagFabricatedTimeline() {
        // video_10 实际特征：相邻章节起点间隔全等（560s × N）
        List<ContentUnderstanding.Section> fabricated = List.of(
                section("一", 0, 560), section("二", 560, 1120), section("三", 1120, 1680), section("四", 1680, 2240));
        assertThat(ContentUnderstandingService.detectUniformSplitIssue(fabricated))
                .contains("均匀切分").contains("560");
        // 间隔不等（真实内容）不命中
        List<ContentUnderstanding.Section> irregular = List.of(
                section("一", 0, 67), section("二", 67, 682), section("三", 682, 1317));
        assertThat(ContentUnderstandingService.detectUniformSplitIssue(irregular)).isNull();
        // 少于 3 章不判定
        assertThat(ContentUnderstandingService.detectUniformSplitIssue(
                List.of(section("一", 0, 100), section("二", 100, 200)))).isNull();
    }

    @Test
    void parseShouldDeriveBoundariesWhenStartEndOmitted() {
        // 新语义：LLM 只归类不输出边界 → 章节保留且边界由知识点时间推导
        String text = """
                {"title": "t", "summary": "s",
                 "sections": [ {"title": "甲章", "summary": "a"}, {"title": "乙章", "summary": "b"} ],
                 "knowledgePoints": [
                   {"name": "p1", "time": 50, "section": 1},
                   {"name": "p2", "time": 250, "section": 2}
                 ]}
                """;
        ContentUnderstanding understanding = ContentUnderstandingService.parseUnderstanding(text, 300);
        assertThat(understanding.getSections()).hasSize(2);
        // 切点 = 各章节首个知识点时间（章节一从 0 起，顺延到章节二内容起点 250）
        assertThat(understanding.getSections().get(0).getStartSec()).isZero();
        assertThat(understanding.getSections().get(0).getEndSec()).isEqualTo(250);
        assertThat(understanding.getSections().get(1).getStartSec()).isEqualTo(250);
        assertThat(understanding.getSections().get(1).getEndSec()).isEqualTo(300);
        assertThat(understanding.getIssue()).isNull();
    }

    @Test
    void parseShouldFlagAndRepairUniformFabricatedTimeline() {
        // 模拟 video_10 场景：LLM 输出均匀切分章节 + 带真实时间的知识点 → 告警且边界被重推导
        String text = """
                {"title": "t", "summary": "s",
                 "sections": [
                   {"title": "一", "start": 0, "end": 100},
                   {"title": "二", "start": 100, "end": 200},
                   {"title": "三", "start": 200, "end": 300}
                 ],
                 "knowledgePoints": [
                   {"name": "p1", "time": 10, "section": 1},
                   {"name": "p2", "time": 130, "section": 2},
                   {"name": "p3", "time": 260, "section": 3}
                 ]}
                """;
        ContentUnderstanding understanding = ContentUnderstandingService.parseUnderstanding(text, 300);
        assertThat(understanding.getIssue()).contains("均匀切分").contains("已按知识点真实时间重推导");
        assertThat(understanding.getSections().get(1).getStartSec()).isEqualTo(130);
        assertThat(understanding.getSections().get(2).getStartSec()).isEqualTo(260);
    }

    @Test
    void parseShouldDropUnlocatableSectionsWhenNoAnchors() {
        // 无锚点且 LLM 未输出边界 → 章节无法定位被丢弃（不落 NULL 边界）
        String text = """
                {"title": "t", "summary": "s",
                 "sections": [ {"title": "甲章"}, {"title": "乙章"} ],
                 "knowledgePoints": [ {"name": "p1"} ]}
                """;
        ContentUnderstanding understanding = ContentUnderstandingService.parseUnderstanding(text, 300);
        assertThat(understanding.getSections()).isEmpty();
        assertThat(understanding.getKnowledgePoints()).hasSize(1);
    }
}
