package com.xueji.agent.ai;

import com.xueji.agent.domain.entity.CourseTranscriptSegment;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 转写审校服务纯函数单测（B26 阶段 1）：LLM 输出解析容错（代码块围栏 / 非法项跳过）与审校 prompt 组装
 */
class TranscriptReviewServiceTest {

    private final TranscriptReviewService service = new TranscriptReviewService();

    private CourseTranscriptSegment segment(int sort, String text) {
        return new CourseTranscriptSegment().setSort(sort).setText(text).setStartSec(0).setEndSec(60);
    }

    @Test
    void parseShouldReadFencedJsonArray() {
        String text = """
                ```json
                [{"sort": 2, "original": "养流效应", "suggestion": "洋流效应", "reason": "同音词"},
                 {"sort": 5, "original": "雪球地壳", "suggestion": "雪球地球", "reason": "假说名"}]
                ```
                """;
        List<CorrectionCandidate> candidates = TranscriptReviewService.parse(text);
        assertThat(candidates).hasSize(2);
        assertThat(candidates.get(0).getSort()).isEqualTo(2);
        assertThat(candidates.get(0).getSuggestion()).isEqualTo("洋流效应");
        assertThat(candidates.get(1).getOriginal()).isEqualTo("雪球地壳");
    }

    @Test
    void parseShouldSkipInvalidItems() {
        String text = """
                [{"sort": 1, "original": "正确", "suggestion": "正确", "reason": "r"},
                 {"sort": -1, "original": "x", "suggestion": "y"},
                 {"original": "缺 sort"},
                 "不是对象"]
                """;
        List<CorrectionCandidate> candidates = TranscriptReviewService.parse(text);
        assertThat(candidates).hasSize(1);
        assertThat(candidates.get(0).getOriginal()).isEqualTo("正确");
    }

    @Test
    void parseShouldReturnEmptyForBlankOrGarbage() {
        assertThat(TranscriptReviewService.parse(null)).isEmpty();
        assertThat(TranscriptReviewService.parse("  ")).isEmpty();
        assertThat(TranscriptReviewService.parse("完全不是 JSON 的输出")).isEmpty();
    }

    @Test
    void buildReviewPromptShouldCarrySortedSegments() {
        List<CourseTranscriptSegment> segments = new ArrayList<>();
        segments.add(segment(0, "第一段"));
        segments.add(segment(1, "第二段"));
        String prompt = TranscriptReviewService.buildReviewPrompt(segments);
        assertThat(prompt).contains("0 | 0-60s | 第一段");
        assertThat(prompt).contains("1 | 0-60s | 第二段");
    }
}
