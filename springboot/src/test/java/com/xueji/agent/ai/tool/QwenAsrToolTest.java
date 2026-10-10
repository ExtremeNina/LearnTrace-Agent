package com.xueji.agent.ai.tool;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ASR 兜底句级估算器单测：多句按字符比例分摊、单句原样、空文本返回空、标点保留、末句对齐区间终点
 */
class QwenAsrToolTest {

    @Test
    void splitShouldProportionallyAllocateTimestamps() {
        AsrSegment giant = new AsrSegment(0, 0, "AAA。BB。CCCC。");
        List<AsrSegment> segments = QwenAsrTool.splitFallbackSegment(giant, 0, 1200);

        // "AAA。"=4 字 / "BB。"=3 字 / "CCCC。"=5 字，总 12 字 → 400ms / 300ms / 500ms
        assertThat(segments).hasSize(3);
        assertThat(segments.get(0).getBeginMs()).isZero();
        assertThat(segments.get(0).getEndMs()).isEqualTo(400);
        assertThat(segments.get(1).getBeginMs()).isEqualTo(400);
        assertThat(segments.get(1).getEndMs()).isEqualTo(700);
        assertThat(segments.get(2).getBeginMs()).isEqualTo(700);
        assertThat(segments.get(2).getEndMs()).isEqualTo(1200);
        // 文本与标点完整保留，拼接还原原文
        StringBuilder text = new StringBuilder();
        for (AsrSegment segment : segments) {
            text.append(segment.getText());
        }
        assertThat(text.toString()).isEqualTo("AAA。BB。CCCC。");
        // 时间单调衔接
        for (int i = 1; i < segments.size(); i++) {
            assertThat(segments.get(i).getBeginMs()).isEqualTo(segments.get(i - 1).getEndMs());
        }
    }

    @Test
    void splitShouldOffsetIntoChunkWindow() {
        AsrSegment giant = new AsrSegment(0, 0, "第一句。第二句。");
        List<AsrSegment> segments = QwenAsrTool.splitFallbackSegment(giant, 280_000, 281_000);

        assertThat(segments).hasSize(2);
        assertThat(segments.get(0).getBeginMs()).isEqualTo(280_000);
        assertThat(segments.get(1).getEndMs()).isEqualTo(281_000);
    }

    @Test
    void splitShouldReturnOriginalForSingleSentence() {
        AsrSegment original = new AsrSegment(0, 0, "没有句末标点的一整段文本");
        List<AsrSegment> segments = QwenAsrTool.splitFallbackSegment(original, 0, 280_000);

        assertThat(segments).hasSize(1);
        assertThat(segments.get(0)).isSameAs(original);
    }

    @Test
    void splitShouldReturnEmptyForBlankText() {
        assertThat(QwenAsrTool.splitFallbackSegment(new AsrSegment(0, 0, "  "), 0, 1000)).isEmpty();
        assertThat(QwenAsrTool.splitFallbackSegment(new AsrSegment(0, 0, ""), 0, 1000)).isEmpty();
    }
}
