package com.xueji.agent.service.impl;

import com.xueji.agent.ai.NotePipelineGraphRunner;
import com.xueji.agent.common.ModelServiceErrors;
import com.xueji.agent.domain.entity.Course;
import com.xueji.agent.exception.BusinessException;
import com.xueji.agent.mapper.CourseTranscriptSegmentMapper;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 模型服务余额不足停止任务单测：Graph 失败且命中 402 时直接上抛 BusinessException
 * （不再走回退链路——回退同样要调 LLM 必然再失败），由 process() 外层 markFailed 置 FAILED
 */
class CoursePipelineBalanceTest {

    private final CourseTranscriptSegmentMapper transcriptMapper = mock(CourseTranscriptSegmentMapper.class);
    private final NotePipelineGraphRunner notePipelineGraphRunner = mock(NotePipelineGraphRunner.class);
    private final CoursePipelineService service = new CoursePipelineService();

    private Course course() {
        return new Course().setId(32L).setUserId(5L).setTitle("video_9 测试课");
    }

    private CoursePipelineService wired() {
        ReflectionTestUtils.setField(service, "transcriptMapper", transcriptMapper);
        ReflectionTestUtils.setField(service, "notePipelineGraphRunner", notePipelineGraphRunner);
        return service;
    }

    @Test
    void balanceErrorShouldStopPipelineInsteadOfFallback() throws Exception {
        when(transcriptMapper.selectList(any())).thenReturn(java.util.List.of());
        Mockito.doThrow(new RuntimeException("HTTP 402 - {\"message\":\"Insufficient Balance\"}"))
                .when(notePipelineGraphRunner)
                .run(any(Course.class), anyList(), anyList(), anyInt(), any(NotePipelineGraphRunner.StageListener.class));

        assertThatThrownBy(() -> ReflectionTestUtils.invokeMethod(wired(), "understandAndRenderNote",
                course(), null, java.util.List.of(), 600))
                .isInstanceOf(BusinessException.class)
                .hasMessage(ModelServiceErrors.BALANCE_MESSAGE);
    }

    @Test
    void nestedBalanceCauseShouldAlsoStopPipeline() throws Exception {
        when(transcriptMapper.selectList(any())).thenReturn(java.util.List.of());
        Mockito.doThrow(new RuntimeException("chat client failed",
                new RuntimeException("REST call failed", new RuntimeException("HTTP 402 Payment Required"))))
                .when(notePipelineGraphRunner)
                .run(any(Course.class), anyList(), anyList(), anyInt(), any(NotePipelineGraphRunner.StageListener.class));

        assertThatThrownBy(() -> ReflectionTestUtils.invokeMethod(wired(), "understandAndRenderNote",
                course(), null, java.util.List.of(), 600))
                .isInstanceOf(BusinessException.class);
    }
}
