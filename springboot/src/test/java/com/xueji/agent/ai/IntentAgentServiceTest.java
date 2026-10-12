package com.xueji.agent.ai;

import com.xueji.agent.ai.IntentAgentService.ProfileAnswer;
import com.xueji.agent.domain.entity.Course;
import com.xueji.agent.mapper.MessageMapper;
import com.xueji.agent.service.CourseService;
import com.xueji.agent.service.ProfileService;
import com.xueji.agent.service.TranscriptionService;
import com.xueji.agent.utils.RedisUtils;
import com.xueji.agent.ws.AgentEventPushService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 意图 Agent 单测（2026-10-11 固定卡改造）：
 * 纯函数——意图判定解析 / 三问解析 / 风格归一 / 静态卡结构 / 标题提取；
 * 分派——ROUTE 处理方式卡确定性分派（Mockito）、PROFILE 解析失败按默认跟学型建课
 */
class IntentAgentServiceTest {

    @TempDir
    Path tempDir;

    // ---- 纯函数 ----

    @Test
    void parseJudgeIntentShouldReadAllBranches() {
        assertThat(IntentAgentService.parseJudgeIntent("""
                ```json
                {"intent": "COURSE"}
                ```
                """)).isEqualTo("COURSE");
        assertThat(IntentAgentService.parseJudgeIntent("{\"intent\": \"transcribe\"}")).isEqualTo("TRANSCRIBE");
        assertThat(IntentAgentService.parseJudgeIntent("{\"intent\": \"unclear\"}")).isEqualTo("UNCLEAR");
    }

    @Test
    void parseJudgeIntentShouldTolerateGarbage() {
        assertThat(IntentAgentService.parseJudgeIntent("我觉得用户想做课程")).isNull();
        assertThat(IntentAgentService.parseJudgeIntent(null)).isNull();
        assertThat(IntentAgentService.parseJudgeIntent("{\"intent\": \"QUESTION\"}")).isNull();
        assertThat(IntentAgentService.parseJudgeIntent("{}")).isNull();
    }

    @Test
    void parseProfileAnswerShouldReadAllFields() {
        ProfileAnswer answer = IntentAgentService.parseProfileAnswer("""
                ```json
                {"goal": "期末备考", "level": "有一定基础", "style": "EXAM_SPRINT",
                 "gradeLevel": "高三", "reply": "好的，按考点速查型为你生成。"}
                ```
                """);
        assertThat(answer).isNotNull();
        assertThat(answer.goal()).isEqualTo("期末备考");
        assertThat(answer.level()).isEqualTo("有一定基础");
        assertThat(answer.style()).isEqualTo("EXAM_SPRINT");
        assertThat(answer.gradeLevel()).isEqualTo("高三");
        assertThat(answer.reply()).contains("考点速查型");
    }

    @Test
    void parseProfileAnswerShouldRejectEmptyOutput() {
        // 全 null 输出视为解析失败（走默认跟学型建课降级）
        assertThat(IntentAgentService.parseProfileAnswer("{\"goal\": null, \"level\": null, \"style\": null}")).isNull();
        assertThat(IntentAgentService.parseProfileAnswer("我理解了你的需求")).isNull();
        assertThat(IntentAgentService.parseProfileAnswer(null)).isNull();
    }

    @Test
    void mapStyleShouldNormalizeChineseAndDefaults() {
        assertThat(IntentAgentService.mapStyle("EXAM_SPRINT")).isEqualTo("EXAM_SPRINT");
        assertThat(IntentAgentService.mapStyle("考点速查型")).isEqualTo("EXAM_SPRINT");
        assertThat(IntentAgentService.mapStyle("考点速查")).isEqualTo("EXAM_SPRINT");
        assertThat(IntentAgentService.mapStyle("跟学型")).isEqualTo("FOLLOW");
        assertThat(IntentAgentService.mapStyle(null)).isEqualTo("FOLLOW");
        assertThat(IntentAgentService.mapStyle("不知道")).isEqualTo("FOLLOW");
    }

    @Test
    void profileCardShouldCarryFixedThreeQuestions() {
        // 三问卡题目与选项为前后端约定的固定文案（讲解形式对应笔记风格渲染变体）
        assertThat(IntentAgentService.PROFILE_QUESTIONS).hasSize(3);
        assertThat(IntentAgentService.PROFILE_QUESTIONS.get(0).title()).isEqualTo("学习目的");
        assertThat(IntentAgentService.PROFILE_QUESTIONS.get(0).options())
                .isEqualTo(List.of("期末备考", "系统学习", "查漏补缺", "兴趣了解"));
        assertThat(IntentAgentService.PROFILE_QUESTIONS.get(1).title()).isEqualTo("知识水平");
        assertThat(IntentAgentService.PROFILE_QUESTIONS.get(2).title()).isEqualTo("讲解形式");
        assertThat(IntentAgentService.PROFILE_QUESTIONS.get(2).options())
                .isEqualTo(List.of("跟学型", "考点速查型"));
    }

    @Test
    void titleFromFileNameShouldStripUploadPrefixes() {
        // 原始文件名原样保留（去扩展名）
        assertThat(IntentAgentService.titleFromFileName(Path.of("x/线性代数第5讲-矩阵乘法.mp4")))
                .isEqualTo("线性代数第5讲-矩阵乘法");
        // xj-chat-video-<时间戳>- 前缀剥离（上传临时文件名）
        assertThat(IntentAgentService.titleFromFileName(Path.of("xj-chat-video-1728382712-我的视频.mp4")))
                .isEqualTo("我的视频");
        // UUID 前缀（8+ 位十六进制-）剥离
        assertThat(IntentAgentService.titleFromFileName(Path.of("a1b2c3d4-如果全球冰封.mp4")))
                .isEqualTo("如果全球冰封");
        // 尾部 -<10 位以上时间戳> 序号剥离
        assertThat(IntentAgentService.titleFromFileName(Path.of("如果全球冰封-1728382712.mp4")))
                .isEqualTo("如果全球冰封");
        // 全部被剥离干净 → 兜底「未命名课程」
        assertThat(IntentAgentService.titleFromFileName(Path.of("xj-chat-video-1728382712-.mp4")))
                .isEqualTo("未命名课程");
    }

    // ---- ROUTE / PROFILE 分派（Mockito；执行分支会校验视频临时文件存在，用 @TempDir 造真实文件） ----

    @TempDir
    Path videoDir;

    private IntentAgentService.PendingVideo newPending(String stage) throws Exception {
        Path video = videoDir.resolve("test-video.mp4");
        Files.writeString(video, "x");
        return new IntentAgentService.PendingVideo(video.toString(), 600, stage);
    }

    @Test
    void resolveRouteShouldDispatchCourseAnswerToProfileCard() throws Exception {
        IntentAgentService service = newService(null);
        RedisUtils redisUtils = (RedisUtils) ReflectionTestUtils.getField(service, "redisUtils");

        var result = service.resolveRoute(1L, 10L, "处理方式：做成课程", newPending("ROUTE"), 600);

        assertThat(result.intentCardJson()).contains("讲解形式").contains("跟学型").contains("考点速查型");
        assertThat(result.text()).contains("视频已收到");
        // pending 重写为 PROFILE 阶段，等待三问回答
        verify(redisUtils).setValueTimeout(eq("xj_intent_pending:10"), anyString(), anyLong());
    }

    @Test
    void resolveRouteShouldDispatchTranscribeAnswerDirectly() throws Exception {
        IntentAgentService service = newService(null);
        TranscriptionService transcriptionService =
                (TranscriptionService) ReflectionTestUtils.getField(service, "transcriptionService");

        var result = service.resolveRoute(1L, 10L, "处理方式：轻量转写", newPending("ROUTE"), 600);

        assertThat(result.intentCardJson()).isNull();
        assertThat(result.text()).contains("已开始转写");
        verify(transcriptionService).submit(eq(1L), eq(10L), anyString(), eq(600));
    }

    @Test
    void resolveRouteShouldFallbackByDurationOnAmbiguousCustomText() throws Exception {
        // 自定义文本「转写成课程」两关键词都命中：≤30min 按默认转写、>30min 按默认建课
        IntentAgentService service = newService(null);
        TranscriptionService transcriptionService =
                (TranscriptionService) ReflectionTestUtils.getField(service, "transcriptionService");

        var shortVideo = service.resolveRoute(1L, 10L, "处理方式：转写成课程", newPending("ROUTE"), 600);
        assertThat(shortVideo.text()).contains("已开始转写");

        var longVideo = service.resolveRoute(1L, 10L, "处理方式：转写成课程", newPending("ROUTE"), 3600);
        assertThat(longVideo.intentCardJson()).contains("讲解形式");
        verify(transcriptionService).submit(eq(1L), eq(10L), anyString(), eq(600));
    }

    @Test
    void resolveProfileShouldFallbackToFollowAndCreateCourseWhenParseFails() throws Exception {
        // 解析链路异常（LLM 不可用）→ 默认跟学型、画像留空，照常建课
        ProfileService profileService = mock(ProfileService.class);
        when(profileService.getByUser(1L)).thenThrow(new RuntimeException("LLM 不可用"));
        CourseService courseService = mock(CourseService.class);
        when(courseService.uploadFromLocal(anyLong(), any(), anyString(), any(), any(), any(), any(), any()))
                .thenReturn(new Course().setId(5L).setTitle("测试课程"));

        IntentAgentService service = new IntentAgentService();
        ReflectionTestUtils.setField(service, "generationChatClient", mock(ChatClient.class));
        ReflectionTestUtils.setField(service, "profileService", profileService);
        ReflectionTestUtils.setField(service, "transcriptionService", mock(TranscriptionService.class));
        ReflectionTestUtils.setField(service, "courseService", courseService);
        ReflectionTestUtils.setField(service, "messageMapper", mock(MessageMapper.class));
        ReflectionTestUtils.setField(service, "pushService", mock(AgentEventPushService.class));
        ReflectionTestUtils.setField(service, "redisUtils", mock(RedisUtils.class));

        var result = service.resolveProfile(1L, 10L, "学习目的：期末备考", newPending("PROFILE"), 600);

        assertThat(result.intentCardJson()).isNull();
        assertThat(result.text()).contains("课程《测试课程》");
        // 入参标题取自临时文件名（titleFromFileName），确认话术标题取建课返回的课程名
        verify(courseService).uploadFromLocal(eq(1L), any(), eq("test-video"), any(), eq((String) null),
                eq("FOLLOW"), eq((String) null), eq((String) null));
    }

    private IntentAgentService newService(ChatClient chatClient) {
        IntentAgentService service = new IntentAgentService();
        ReflectionTestUtils.setField(service, "generationChatClient", chatClient);
        ReflectionTestUtils.setField(service, "profileService", mock(ProfileService.class));
        ReflectionTestUtils.setField(service, "transcriptionService", mock(TranscriptionService.class));
        ReflectionTestUtils.setField(service, "courseService", mock(CourseService.class));
        ReflectionTestUtils.setField(service, "messageMapper", mock(MessageMapper.class));
        ReflectionTestUtils.setField(service, "pushService", mock(AgentEventPushService.class));
        ReflectionTestUtils.setField(service, "redisUtils", mock(RedisUtils.class));
        return service;
    }
}
