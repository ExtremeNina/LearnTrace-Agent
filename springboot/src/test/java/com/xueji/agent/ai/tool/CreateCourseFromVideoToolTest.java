package com.xueji.agent.ai.tool;

import com.xueji.agent.domain.entity.Course;
import com.xueji.agent.domain.entity.Message;
import com.xueji.agent.mapper.MessageMapper;
import com.xueji.agent.service.CourseService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.chat.model.ToolContext;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.when;

/**
 * 对话视频创建课程工具单测（B11 分流）：建课 + course_task 占位消息落库，
 * 文件缺失返回 CREATE_FAILED，标题缺省从文件名推导
 */
@ExtendWith(MockitoExtension.class)
class CreateCourseFromVideoToolTest {

    @Mock
    private CourseService courseService;

    @Mock
    private MessageMapper messageMapper;

    @InjectMocks
    private CreateCourseFromVideoTool tool;

    private ToolContext context(String tempPath) {
        return new ToolContext(Map.of("userId", 5L, "conversationId", 9L, "videoTempPath", tempPath));
    }

    @Test
    void shouldCreateCourseAndPlaceholderMessage(@org.junit.jupiter.api.io.TempDir Path dir) throws Exception {
        Path video = Files.createTempFile(dir, "uuid1234-video5_", ".mp4");
        Files.writeString(video, "fake");
        when(courseService.uploadFromLocal(eq(5L), any(Path.class), anyString(), isNull()))
                .thenReturn(new Course().setId(66L).setTitle("video5_如果全球冰封").setUserId(5L));

        String result = tool.createCourseFromVideo(null, null, context(video.toString()));

        assertThat(result).isEqualTo("CREATE_OK");
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Message> captor = ArgumentCaptor.forClass(Message.class);
        org.mockito.Mockito.verify(messageMapper).insert(captor.capture());
        Message placeholder = captor.getValue();
        assertThat(placeholder.getMsgType()).isEqualTo("course_task");
        assertThat(placeholder.getCourseId()).isEqualTo(66L);
        assertThat(placeholder.getPayload()).contains("\"courseId\":66").contains("\"status\":\"processing\"");
    }

    @Test
    void shouldReturnFailedWhenTempFileMissing() {
        String missing = Path.of(System.getProperty("java.io.tmpdir"), "xj-no-such-course-video.mp4").toString();
        String result = tool.createCourseFromVideo(null, null, context(missing));
        assertThat(result).startsWith("CREATE_FAILED").contains("重新上传");
    }

    @Test
    void shouldUseLlmTitleWhenProvided(@org.junit.jupiter.api.io.TempDir Path dir) throws Exception {
        Path video = Files.createTempFile(dir, "v_", ".mp4");
        when(courseService.uploadFromLocal(eq(5L), any(Path.class), eq("如果全球冰封"), eq("重点讲原理")))
                .thenReturn(new Course().setId(67L).setTitle("如果全球冰封").setUserId(5L));

        String result = tool.createCourseFromVideo("如果全球冰封", "重点讲原理", context(video.toString()));

        assertThat(result).isEqualTo("CREATE_OK");
    }

    @Test
    void titleFromFileNameShouldStripUploadPrefixes() {
        // 原始文件名原样保留（去扩展名）
        assertThat(CreateCourseFromVideoTool.titleFromFileName(Path.of("x/线性代数第5讲-矩阵乘法.mp4")))
                .isEqualTo("线性代数第5讲-矩阵乘法");
        // UUID 前缀（>8 位十六进制-）剥离（该实现要求前缀长于 8 才剥离）
        assertThat(CreateCourseFromVideoTool.titleFromFileName(Path.of("a1b2c3d4e5f6-如果全球冰封.mp4")))
                .isEqualTo("如果全球冰封");
        // xj-chat-video-<时间戳>- 前缀剥离
        assertThat(CreateCourseFromVideoTool.titleFromFileName(Path.of("xj-chat-video-1728382712-我的视频.mkv")))
                .isEqualTo("我的视频");
        // 尾部 -<10 位以上时间戳> 序号剥离
        assertThat(CreateCourseFromVideoTool.titleFromFileName(Path.of("如果全球冰封-1728382712.mp4")))
                .isEqualTo("如果全球冰封");
        // 全部被剥离干净 → 兜底「未命名课程」
        assertThat(CreateCourseFromVideoTool.titleFromFileName(Path.of("xj-chat-video-1728382712-.mp4")))
                .isEqualTo("未命名课程");
    }

    @Test
    void isMeaningfulTitleShouldRejectBlankAndTemplateNames() {
        assertThat(CreateCourseFromVideoTool.isMeaningfulTitle(null)).isFalse();
        assertThat(CreateCourseFromVideoTool.isMeaningfulTitle("  ")).isFalse();
        assertThat(CreateCourseFromVideoTool.isMeaningfulTitle("课程视频（45 分钟）")).isFalse();
        assertThat(CreateCourseFromVideoTool.isMeaningfulTitle("视频课程：第一章")).isFalse();
        assertThat(CreateCourseFromVideoTool.isMeaningfulTitle("线性代数第5讲")).isTrue();
    }
}
