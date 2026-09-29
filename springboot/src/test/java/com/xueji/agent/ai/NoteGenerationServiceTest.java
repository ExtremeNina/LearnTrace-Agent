package com.xueji.agent.ai;

import com.xueji.agent.domain.entity.CourseFrame;
import com.xueji.agent.domain.entity.CourseTranscriptSegment;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 笔记生成的用户消息组装：转写 / 关键帧 / 用户期望的拼接规则
 */
class NoteGenerationServiceTest {

    private CourseTranscriptSegment segment(int start, int end, String text) {
        return new CourseTranscriptSegment()
                .setCourseId(1L)
                .setStartSec(start)
                .setEndSec(end)
                .setText(text)
                .setSort(0);
    }

    private CourseFrame frame(int sec, String text) {
        return new CourseFrame()
                .setCourseId(1L)
                .setTimeSec(sec)
                .setOssKey("https://oss/frame.jpg")
                .setOcrText(text)
                .setOcrStatus("SUCCESS");
    }

    @Test
    void buildUserContentShouldAssembleTranscriptFramesAndExpectations() {
        String content = NoteGenerationService.buildUserContent(
                List.of(segment(3, 280, "大家好，欢迎来到学迹。"), segment(280, 560, "今天讲微分中值定理。")),
                List.of(frame(0, "## 第 12 讲：微分中值定理"), frame(300, "罗尔定理三个条件")),
                "我不太理解罗尔定理的几何意义，请重点展开",
                713);

        assertThat(content).contains("[语音转写文本（句级分段，含起止秒）]");
        assertThat(content).contains("[3-280s] 大家好，欢迎来到学迹。");
        assertThat(content).contains("[280-560s] 今天讲微分中值定理。");
        assertThat(content).contains("[画面识别文本（关键帧，含时间戳）]");
        assertThat(content).contains("[第 0s 画面]").contains("微分中值定理");
        assertThat(content).contains("[第 300s 画面]").contains("罗尔定理三个条件");
        assertThat(content).contains("[用户的特别要求]");
        assertThat(content).contains("我不太理解罗尔定理的几何意义");
        assertThat(content).contains("视频时长：713 秒");
    }

    @Test
    void blankExpectationsShouldBeOmitted() {
        String content = NoteGenerationService.buildUserContent(
                List.of(segment(0, 10, "内容")), List.of(frame(0, "画面")), "  ", 10);
        assertThat(content).doesNotContain("[用户的特别要求]");
    }

    @Test
    void failedFramesShouldBeSkipped() {
        CourseFrame failed = frame(5, "不应该出现").setOcrStatus("FAILED").setOcrText(null);
        String content = NoteGenerationService.buildUserContent(
                List.of(segment(0, 10, "内容")), List.of(failed), null, 10);
        assertThat(content).doesNotContain("不应该出现");
        assertThat(content).contains("（本视频未获得有效的画面识别结果）");
    }

    @Test
    void missingTranscriptShouldDegradeGracefully() {
        String content = NoteGenerationService.buildUserContent(
                List.of(), List.of(frame(0, "画面")), null, 10);
        assertThat(content).contains("（本视频未获得语音转写结果）");
        assertThat(content).contains("[第 0s 画面]");
    }
}
