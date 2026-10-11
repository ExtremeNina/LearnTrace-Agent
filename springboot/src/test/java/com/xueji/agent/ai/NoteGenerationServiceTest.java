package com.xueji.agent.ai;

import com.xueji.agent.ai.prompt.AgentPrompts;
import com.xueji.agent.domain.entity.ContentDocument;
import com.xueji.agent.domain.entity.ContentSection;
import com.xueji.agent.domain.entity.Course;
import com.xueji.agent.domain.entity.CourseFrame;
import com.xueji.agent.domain.entity.CourseTranscriptSegment;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 笔记生成的用户消息组装：转写 / 关键帧 / 用户期望的拼接规则；
 * 学习者上下文注入（三问卡画像 + 特别要求）与风格变体选择（2026-10-11）
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

    @Test
    void normalizeTimestampsShouldConvertSecondsAndHmsForms() {
        String text = "讲到了输入空间 [320s]，易错点见 [45秒]，时长跨段 [1:15:30]，正常 [05:20] 保持不变";
        String normalized = NoteGenerationService.normalizeTimestamps(text);
        assertThat(normalized)
                .contains("[05:20]").contains("[00:45]").contains("[75:30]").contains("[05:20]")
                .doesNotContain("320s").doesNotContain("45秒").doesNotContain("1:15:30");
    }

    @Test
    void normalizeTimestampsShouldLeaveNonTimestampBracketsAlone() {
        String text = "公式 [a+b] 不是时间戳，正常胶囊 [03:07] 保持原样";
        String normalized = NoteGenerationService.normalizeTimestamps(text);
        assertThat(normalized).contains("[a+b]").contains("[03:07]");
    }

    @Test
    void dedupeShouldCollapseConsecutiveDuplicateTimestamps() {
        // 转写粒度坍缩场景：成片复制的同一时间戳折叠为首个（重复项之间的分隔符一并移除，末项后的"；"保留）
        String text = "补码的定义如下 [04:40]，[04:40]，[04:40]、[04:40]；注意溢出 [04:40]";
        String deduped = NoteGenerationService.dedupeConsecutiveTimestamps(text);
        assertThat(deduped).isEqualTo("补码的定义如下 [04:40]；注意溢出 [04:40]");
    }

    @Test
    void dedupeShouldKeepNonConsecutiveDuplicates() {
        String text = "开头 [01:00]，中段 [02:00]，回到同一位置再讲 [01:00]";
        assertThat(NoteGenerationService.dedupeConsecutiveTimestamps(text)).isEqualTo(text);
    }

    @Test
    void dedupeShouldCollapseLongRunsStably() {
        String text = "[04:40] [04:40] [04:40] [04:40] [04:40] 内容";
        assertThat(NoteGenerationService.dedupeConsecutiveTimestamps(text)).isEqualTo("[04:40] 内容");
    }

    // ---- 学习者上下文注入（意图三问卡画像 + 特别要求） ----

    private Course courseWithProfile() {
        return new Course()
                .setLearnerGoal("期末备考")
                .setLearnerLevel("有一定基础")
                .setExpectations("多讲例题");
    }

    @Test
    void learnerContextBlockShouldAssembleNonBlankFields() {
        String context = NoteGenerationService.learnerContextBlock(courseWithProfile());
        assertThat(context)
                .contains("[学习者目标]\n期末备考")
                .contains("[知识水平]\n有一定基础")
                .contains("[用户的特别要求]\n多讲例题");
    }

    @Test
    void learnerContextBlockShouldBeEmptyWithoutProfile() {
        assertThat(NoteGenerationService.learnerContextBlock(new Course())).isEmpty();
    }

    @Test
    void skeletonUserContentShouldComposeWithLearnerContext() {
        // 生产链路：builder 产出 + learnerContextBlock 拼接（renderNotePaged 同款组合）
        ContentDocument document = new ContentDocument().setSummary("课程整体介绍");
        List<ContentSection> sections = List.of(new ContentSection().setTitle("第一章").setStartSec(0).setEndSec(60));
        String content = NoteGenerationService.buildSkeletonUserContent(document, sections, 600, List.of())
                + NoteGenerationService.learnerContextBlock(courseWithProfile());
        assertThat(content).contains("[章节列表]").contains("第一章");
        assertThat(content).contains("[学习者目标]").contains("期末备考");
    }

    // ---- 笔记风格变体 ----

    @Test
    void summaryStyleDirectiveShouldDifferByStyle() {
        assertThat(AgentPrompts.summaryStyleDirective("EXAM_SPRINT")).contains("考前清单");
        assertThat(AgentPrompts.summaryStyleDirective("FOLLOW")).contains("复习路径建议");
        assertThat(AgentPrompts.summaryStyleDirective(null)).contains("复习路径建议");
    }

    @Test
    void chapterPromptVariantsShouldCarryStyleNamesAndTimestampBan() {
        assertThat(AgentPrompts.DOCUMENT_NOTE_CHAPTER_PROMPT_FOLLOW).contains("跟学型").contains("引入 → 讲解 → 例子");
        assertThat(AgentPrompts.DOCUMENT_NOTE_CHAPTER_PROMPT_EXAM_SPRINT).contains("考点速查型").contains("结论/公式 → 易错点 → 典型例");
        // 两个变体都必须保留方案 B 约束：禁时间戳、禁二级标题、标题照用输入名
        for (String prompt : List.of(AgentPrompts.DOCUMENT_NOTE_CHAPTER_PROMPT_FOLLOW,
                AgentPrompts.DOCUMENT_NOTE_CHAPTER_PROMPT_EXAM_SPRINT)) {
            assertThat(prompt).contains("禁止输出任何时间戳").contains("不要输出章节标题").contains("完全一致");
        }
    }
}
