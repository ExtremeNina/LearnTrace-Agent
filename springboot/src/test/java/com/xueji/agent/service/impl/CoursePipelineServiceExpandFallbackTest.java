package com.xueji.agent.service.impl;

import com.xueji.agent.ai.tool.AsrSegment;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ASR 伪句级展开：单段≈分片全长时按真实区间切分；正常句级结果不误切
 */
class CoursePipelineServiceExpandFallbackTest {

    private static final long CHUNK_MS = 280_000L;

    private AsrSegment segment(int beginMs, int endMs, String text) {
        return new AsrSegment(beginMs, endMs, text);
    }

    @Test
    void pseudoSentenceSpanningWholeChunkShouldBeSplit() {
        // 实测主形态：端点返回单句形态，起止时间覆盖整个 280s 分片
        String text = "大家好欢迎来到今天的课程。今天我们讲洛必达法则！先看第一个条件，分子分母分别求导。然后再判断极限是否存在？";
        List<AsrSegment> result = CoursePipelineService.expandFallbackSegments(
                List.of(segment(0, 280_000, text)), CHUNK_MS);

        assertThat(result.size()).isGreaterThan(1);
        assertThat(result.get(0).getBeginMs()).isZero();
        assertThat(result.get(result.size() - 1).getEndMs()).isEqualTo(280_000);
        // 每个子段文本都在原文中且按句末标点切分
        assertThat(result.get(0).getText()).endsWith("。");
        long totalChars = result.stream().mapToLong(s -> s.getText().length()).sum();
        assertThat(totalChars).isEqualTo(text.length());
    }

    @Test
    void pseudoSentenceWithOffsetSpanShouldSplitWithinRealRange() {
        // 带偏移分片：真实区间 [20s, 280s] 内切分
        String text = "擒贼先擒王，抓大头的意思就是我们要抓住主要部分。当分子分母最高次幂相同时，直接比较次数！";
        List<AsrSegment> result = CoursePipelineService.expandFallbackSegments(
                List.of(segment(20_000, 280_000, text)), 260_000L);

        assertThat(result.size()).isGreaterThan(1);
        assertThat(result.get(0).getBeginMs()).isEqualTo(20_000);
        assertThat(result.get(result.size() - 1).getEndMs()).isEqualTo(280_000);
    }

    @Test
    void normalShortSingleSentenceShouldNotBeSplit() {
        // 单句时长明显小于分片：真实句级结果，原样返回
        AsrSegment normal = segment(1000, 20_000, "这是一个正常长度的句子");
        List<AsrSegment> result = CoursePipelineService.expandFallbackSegments(List.of(normal), CHUNK_MS);
        assertThat(result).hasSize(1).containsExactly(normal);
    }

    @Test
    void multiSentenceResultShouldPassThrough() {
        List<AsrSegment> sentenceLevel = List.of(
                segment(0, 5000, "第一句。"),
                segment(5000, 12_000, "第二句！"));
        List<AsrSegment> result = CoursePipelineService.expandFallbackSegments(sentenceLevel, CHUNK_MS);
        assertThat(result).hasSize(2).containsExactlyElementsOf(sentenceLevel);
    }

    @Test
    void zeroMarkedFallbackShouldStillSplitAcrossChunk() {
        List<AsrSegment> result = CoursePipelineService.expandFallbackSegments(
                List.of(segment(0, 0, "第一句。第二句！第三句？")), CHUNK_MS);
        assertThat(result.size()).isEqualTo(3);
        assertThat(result.get(result.size() - 1).getEndMs()).isEqualTo(CHUNK_MS);
    }
}
