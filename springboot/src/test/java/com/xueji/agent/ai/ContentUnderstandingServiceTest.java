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
}
