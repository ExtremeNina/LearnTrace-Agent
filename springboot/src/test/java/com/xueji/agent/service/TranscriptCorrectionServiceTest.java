package com.xueji.agent.service;

import com.xueji.agent.ai.CorrectionCandidate;
import com.xueji.agent.domain.entity.Course;
import com.xueji.agent.domain.entity.CourseFrame;
import com.xueji.agent.domain.entity.CourseTranscriptSegment;
import com.xueji.agent.mapper.CourseTranscriptSegmentMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 转写修正应用服务单测（B26 阶段 1）：帧 OCR 佐证命中 → 自动应用（text_corrected），
 * 无佐证 → SUGGESTED 仅记元数据；已修正/原文不含候选词跳过
 */
@ExtendWith(MockitoExtension.class)
class TranscriptCorrectionServiceTest {

    @Mock
    private CourseTranscriptSegmentMapper segmentMapper;

    @InjectMocks
    private TranscriptCorrectionService service;

    private Course course() {
        return new Course().setId(7L).setUserId(5L).setTitle("测试课程");
    }

    private CourseTranscriptSegment segment(String text) {
        return new CourseTranscriptSegment().setSort(0).setText(text).setStartSec(150).setEndSec(240);
    }

    @Test
    void ocrEvidenceShouldAutoApplyCorrection() {
        when(segmentMapper.updateById(any())).thenReturn(1);

        int applied = service.apply(course(),
                List.of(segment("养流效应会导致温度下降")),
                List.of(new CorrectionCandidate(0, "养流效应", "洋流效应", "同音词")),
                List.of(frame(200, "洋流效应示意图")));

        assertThat(applied).isEqualTo(1);
        verify(segmentMapper).updateById(any());
    }

    @Test
    void withoutEvidenceShouldMarkSuggestedOnly() {
        when(segmentMapper.updateById(any())).thenReturn(1);

        int applied = service.apply(course(),
                List.of(segment("雪球地壳假说认为")),
                List.of(new CorrectionCandidate(0, "雪球地壳", "雪球地球", "假说名")),
                List.of());

        assertThat(applied).isEqualTo(0);
        verify(segmentMapper).updateById(any());
    }

    @Test
    void alreadyCorrectedSegmentShouldBeSkipped() {
        CourseTranscriptSegment segment = segment("养流效应会导致温度下降")
                .setTextCorrected("洋流效应").setCorrectionMeta("{\"status\":\"APPLIED\"}");
        int applied = service.apply(course(),
                List.of(segment),
                List.of(new CorrectionCandidate(0, "养流效应", "洋流效应", "同音词")),
                List.of(frame(200, "洋流中断示意图")));

        assertThat(applied).isEqualTo(0);
        verify(segmentMapper, times(0)).updateById(any());
    }

    @Test
    void candidateWordMissingInTextShouldBeSkipped() {
        int applied = service.apply(course(),
                List.of(segment("文本里根本没有这个词")),
                List.of(new CorrectionCandidate(0, "养流效应", "洋流效应", "同音词")),
                List.of());
        assertThat(applied).isEqualTo(0);
        verify(segmentMapper, times(0)).updateById(any());
    }

    private CourseFrame frame(int sec, String text) {
        return new CourseFrame().setTimeSec(sec).setOcrText(text).setOcrStatus("SUCCESS");
    }
}
