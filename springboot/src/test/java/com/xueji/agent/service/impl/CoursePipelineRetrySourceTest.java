package com.xueji.agent.service.impl;

import com.xueji.agent.domain.entity.Course;
import com.xueji.agent.domain.enums.CourseStatus;
import com.xueji.agent.exception.UploadException;
import com.xueji.agent.mapper.CourseMapper;
import com.xueji.agent.utils.AliUploadUtils;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * 视频来源解析（重试回源）：空白 tempPath 不再被 Files.exists(Path.of("")) 陷阱绕过，
 * OSS 已传视频回源继续流程，双缺失引导重新上传
 */
@ExtendWith(MockitoExtension.class)
class CoursePipelineRetrySourceTest {

    @Mock
    private CourseMapper courseMapper;

    @Mock
    private AliUploadUtils aliUploadUtils;

    @InjectMocks
    private CoursePipelineService service;

    private Course course(Long id, String videoOssKey) {
        return new Course().setId(id).setVideoOssKey(videoOssKey);
    }

    @Test
    void blankTempPathWithOssKeyShouldDownloadAndResume() {
        Course course = course(39L, "https://bucket.oss.example.com/course/39/video.mp4");

        Path result = service.resolveVideo(course, "", null);

        assertThat(result).isNotNull();
        assertThat(result.getFileName().toString()).isEqualTo("retry-39.mp4");
        assertThat(result.getParent().getFileName().toString()).isEqualTo("xueji-upload");
        verify(aliUploadUtils).downloadTo(eq("https://bucket.oss.example.com/course/39/video.mp4"), eq(result));
        // 回源成功不置失败状态
        verify(courseMapper, never()).updateById(any(Course.class));
    }

    @Test
    void blankTempPathWithoutOssKeyShouldGuideReupload() {
        Course course = course(39L, null);

        Path result = service.resolveVideo(course, "", null);

        assertThat(result).isNull();
        ArgumentCaptor<Course> captor = ArgumentCaptor.forClass(Course.class);
        verify(courseMapper, atLeastOnce()).updateById(captor.capture());
        List<Course> saved = captor.getAllValues();
        assertThat(saved.get(saved.size() - 1).getStatus()).isEqualTo(CourseStatus.FAILED);
        assertThat(saved.get(saved.size() - 1).getErrorMsg()).contains("重新上传");
    }

    @Test
    void ossDownloadFailureShouldFailWithReadableMessage() {
        Course course = course(39L, "https://bucket.oss.example.com/course/39/video.mp4");
        doThrow(new UploadException()).when(aliUploadUtils).downloadTo(anyString(), any(Path.class));

        Path result = service.resolveVideo(course, "", null);

        assertThat(result).isNull();
        ArgumentCaptor<Course> captor = ArgumentCaptor.forClass(Course.class);
        verify(courseMapper, atLeastOnce()).updateById(captor.capture());
        List<Course> saved = captor.getAllValues();
        assertThat(saved.get(saved.size() - 1).getStatus()).isEqualTo(CourseStatus.FAILED);
        assertThat(saved.get(saved.size() - 1).getErrorMsg()).contains("重新上传");
    }

    @Test
    void existingLocalFileShouldBeUsedDirectly() throws Exception {
        Path local = Files.createTempFile("xj-retry-test-", ".mp4");
        try {
            Course course = course(39L, "https://bucket.oss.example.com/course/39/video.mp4");

            Path result = service.resolveVideo(course, local.toString(), null);

            assertThat(result).isEqualTo(local);
            verify(aliUploadUtils, never()).downloadTo(anyString(), any(Path.class));
        } finally {
            Files.deleteIfExists(local);
        }
    }
}
