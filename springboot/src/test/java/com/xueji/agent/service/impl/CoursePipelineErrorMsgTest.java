package com.xueji.agent.service.impl;

import com.xueji.agent.domain.entity.Course;
import com.xueji.agent.domain.entity.Message;
import com.xueji.agent.mapper.CourseMapper;
import com.xueji.agent.mapper.MessageMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 课程流水线失败信息修复单测（B28）：
 * ①updateStatus 截断收口——error_msg 超长统一截到 2000，不再触发 MysqlDataTruncation 掩盖真实根因
 * ②markFailed 写库失败降级——短文案重试一次，状态落库且失败事件必发
 */
class CoursePipelineErrorMsgTest {

    private final CourseMapper courseMapper = mock(CourseMapper.class);
    private final MessageMapper messageMapper = mock(MessageMapper.class);
    private final CoursePipelineService service = new CoursePipelineService();

    private CoursePipelineService wired() {
        ReflectionTestUtils.setField(service, "courseMapper", courseMapper);
        ReflectionTestUtils.setField(service, "messageMapper", messageMapper);
        return service;
    }

    private Course course() {
        return new Course().setId(31L).setUserId(5L).setTitle("video_9 测试课");
    }

    @Test
    void updateStatusShouldTruncateErrorMsgToColumnLimit() {
        String huge = "处理异常：" + "x".repeat(5000);

        ReflectionTestUtils.invokeMethod(wired(), "updateStatus", course(), "FAILED", huge);

        ArgumentCaptor<Course> captor = ArgumentCaptor.forClass(Course.class);
        verify(courseMapper).updateById(captor.capture());
        assertThat(captor.getValue().getErrorMsg())
                .hasSize(CoursePipelineService.ERROR_MSG_MAX)
                .startsWith("处理异常：");
    }

    @Test
    void markFailedShouldRetryWithShortMessageWhenWriteFails() {
        // 第一次写库（完整错误信息）溢出抛异常 → 降级短文案重试一次成功（后续 stage 持久化共用同一实体）
        when(courseMapper.updateById(any(Course.class)))
                .thenThrow(new RuntimeException("Data too long for column 'error_msg'"))
                .thenReturn(1);

        ReflectionTestUtils.invokeMethod(wired(), "markFailed", course(), "处理异常：" + "x".repeat(5000), null);

        ArgumentCaptor<Course> captor = ArgumentCaptor.forClass(Course.class);
        verify(courseMapper, times(3)).updateById(captor.capture());
        assertThat(captor.getAllValues().get(1).getErrorMsg()).hasSizeLessThanOrEqualTo(200);
        assertThat(captor.getAllValues().get(1).getStatus()).isEqualTo("FAILED");
    }

    @Test
    void markFailedShouldWriteNormallyWhenMessageFits() {
        when(courseMapper.updateById(any(Course.class))).thenReturn(1);

        ReflectionTestUtils.invokeMethod(wired(), "markFailed", course(), "本地上传文件已丢失，请删除后重新上传该网课", null);

        // updateStatus 一次 + pushCourseStage 的 stage 持久化一次
        ArgumentCaptor<Course> captor = ArgumentCaptor.forClass(Course.class);
        verify(courseMapper, times(2)).updateById(captor.capture());
        assertThat(captor.getAllValues().get(0).getErrorMsg()).contains("本地上传文件已丢失");
        assertThat(captor.getAllValues().get(0).getStatus()).isEqualTo("FAILED");
    }
}
