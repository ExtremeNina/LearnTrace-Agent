package com.xueji.agent.service.impl;

import com.xueji.agent.ai.NotePipelineGraphRunner;
import com.xueji.agent.common.ModelServiceErrors;
import com.xueji.agent.domain.entity.Course;
import com.xueji.agent.exception.BusinessException;
import com.xueji.agent.mapper.CourseMapper;
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
 * Graph 失败快速终止单测（回退链路已移除）：
 * 余额不足 → BusinessException(充值文案)；其余 Graph 失败 → BusinessException(可读失败文案)，均不再降级为转写直出笔记
 */
class CoursePipelineBalanceTest {

    private final CourseTranscriptSegmentMapper transcriptMapper = mock(CourseTranscriptSegmentMapper.class);
    private final NotePipelineGraphRunner notePipelineGraphRunner = mock(NotePipelineGraphRunner.class);
    private final CourseMapper courseMapper = mock(CourseMapper.class);
    private final CoursePipelineService service = new CoursePipelineService();

    private Course course() {
        return new Course().setId(32L).setUserId(5L).setTitle("video_9 测试课");
    }

    private CoursePipelineService wired() {
        ReflectionTestUtils.setField(service, "transcriptMapper", transcriptMapper);
        ReflectionTestUtils.setField(service, "notePipelineGraphRunner", notePipelineGraphRunner);
        ReflectionTestUtils.setField(service, "courseMapper", courseMapper);
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

    @Test
    void graphFailureShouldFailFastWithoutFallback() throws Exception {
        // 回退链路已移除：非余额类 Graph 失败同样上抛（可读文案），课程由 process() 置 FAILED
        when(transcriptMapper.selectList(any())).thenReturn(java.util.List.of());
        Mockito.doThrow(new RuntimeException("内容理解（Reduce 合并）失败：Expected a ',' or ']' at 19877"))
                .when(notePipelineGraphRunner)
                .run(any(Course.class), anyList(), anyList(), anyInt(), any(NotePipelineGraphRunner.StageListener.class));

        assertThatThrownBy(() -> ReflectionTestUtils.invokeMethod(wired(), "understandAndRenderNote",
                course(), null, java.util.List.of(), 600))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("AI 笔记与课后习题生成失败")
                .hasMessageContaining("请在网课列表重试");
    }
}
