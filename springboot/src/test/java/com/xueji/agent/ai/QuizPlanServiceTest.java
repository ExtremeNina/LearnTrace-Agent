package com.xueji.agent.ai;

import com.xueji.agent.ai.QuizPlanService.PlanItem;
import com.xueji.agent.domain.entity.CourseTranscriptSegment;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 出题 Agent（命题规划）纯函数单测：规划解析容错、规划 / 写题用户消息组装
 */
class QuizPlanServiceTest {

    private CourseTranscriptSegment segment(int start, int end, String text) {
        return new CourseTranscriptSegment().setStartSec(start).setEndSec(end).setText(text).setSort(0);
    }

    @Test
    void parsePlanShouldReadPlanItemsAndSkipInvalid() {
        List<PlanItem> plan = QuizPlanService.parsePlan("""
                ```json
                {"count": 3, "plan": [
                  {"knowledgePoint": "行列式的几何含义", "questionType": "概念理解", "angle": "面积比值与降维", "timeSec": 2150},
                  {"knowledgePoint": "", "questionType": "无效项"},
                  {"knowledgePoint": "特征值求解", "questionType": "应用计算", "angle": "det(A-λI)=0 流程", "timeSec": -3}
                ]}
                ```
                """);
        assertThat(plan).hasSize(2);
        assertThat(plan.get(0).getKnowledgePoint()).isEqualTo("行列式的几何含义");
        assertThat(plan.get(0).getTimeSec()).isEqualTo(2150);
        assertThat(plan.get(1).getTimeSec()).isNull();
    }

    @Test
    void parsePlanShouldTolerateGarbage() {
        assertThat(QuizPlanService.parsePlan("无法规划")).isEmpty();
        assertThat(QuizPlanService.parsePlan(null)).isEmpty();
    }

    @Test
    void planPromptShouldCarryNoteProfileAndFeedback() {
        String prompt = QuizPlanService.buildPlanPrompt(
                "# 线性代数笔记\n\n本质是线性变换。", null, "数量过少");
        assertThat(prompt).contains("[AI 笔记全文]")
                .contains("# 线性代数笔记")
                .contains("规划反馈").contains("数量过少");
        // 无画像不出现画像段
        assertThat(prompt).doesNotContain("学习者画像");
    }

    @Test
    void writePromptShouldCarryPlanTranscriptAndJudgeFeedback() {
        PlanItem item = new PlanItem();
        try {
            java.lang.reflect.Field f = PlanItem.class.getDeclaredField("knowledgePoint");
            f.setAccessible(true);
            f.set(item, "行列式的几何含义");
            f = PlanItem.class.getDeclaredField("questionType");
            f.setAccessible(true);
            f.set(item, "概念理解");
            f = PlanItem.class.getDeclaredField("angle");
            f.setAccessible(true);
            f.set(item, "面积比值与降维");
            f = PlanItem.class.getDeclaredField("timeSec");
            f.setAccessible(true);
            f.set(item, 2150);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
        String prompt = QuizPlanService.buildWritePrompt(
                List.of(item),
                List.of(segment(2100, 2200, "行列式为零意味着空间被压缩降维。")),
                null,
                List.of("第 1 题脱离材料"));
        assertThat(prompt).contains("一条对应一道题")
                .contains("知识点：行列式的几何含义").contains("角度：面积比值与降维").contains("时间点：2150s")
                .contains("行列式为零意味着空间被压缩降维")
                .contains("判题反馈").contains("第 1 题脱离材料");
    }

    // ---- 写题分批（2026-10-11 根治大课单次全量输出截断） ----

    private PlanItem planItem(String knowledgePoint) {
        PlanItem item = new PlanItem();
        try {
            java.lang.reflect.Field f = PlanItem.class.getDeclaredField("knowledgePoint");
            f.setAccessible(true);
            f.set(item, knowledgePoint);
            f = PlanItem.class.getDeclaredField("questionType");
            f.setAccessible(true);
            f.set(item, "概念理解");
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
        return item;
    }

    private String questionsJson(String marker, int count) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < count; i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append("{\"question\":\"").append(marker).append(" 第").append(i + 1)
                    .append("题\",\"answer\":\"答\",\"analysis\":\"析\"}");
        }
        return sb.append(']').toString();
    }

    @Test
    void writePromptShouldOnlyContainGivenBatchItems() {
        // 分批写题：传入的 batch 只含本批规划项，prompt 不出现其他批的知识点
        List<PlanItem> plan = List.of(
                planItem("第一批知识点甲"), planItem("第一批知识点乙"), planItem("第二批知识点丙"));
        String prompt = QuizPlanService.buildWritePrompt(
                plan.subList(0, 2), List.of(segment(0, 10, "内容")), null, null);
        assertThat(prompt)
                .contains("第一批知识点甲").contains("第一批知识点乙")
                .doesNotContain("第二批知识点丙");
    }

    @Test
    void writeQuizShouldSplitIntoBatchesAndConcatenateInOrder() {
        // 7 个规划项 + 批大小 6 → 两批；两批输出按序拼接为完整题目列表
        ChatClient chatClient = mock(ChatClient.class, org.mockito.Mockito.RETURNS_DEEP_STUBS);
        when(chatClient.prompt().system(anyString()).user(anyString()).call().content())
                .thenReturn(questionsJson("甲批", 6), questionsJson("乙批", 1));
        QuizPlanService service = new QuizPlanService();
        org.springframework.test.util.ReflectionTestUtils.setField(service, "generationChatClient", chatClient);

        List<PlanItem> plan = new java.util.ArrayList<>();
        for (int i = 0; i < 7; i++) {
            plan.add(planItem("知识点" + (i + 1)));
        }

        List<QuizAgentService.QuizQuestion> questions = service.writeQuiz(plan, List.of(segment(0, 10, "内容")), null, null);

        assertThat(questions).hasSize(7);
        assertThat(questions.get(0).getQuestion()).startsWith("甲批");
        assertThat(questions.get(5).getQuestion()).startsWith("甲批");
        assertThat(questions.get(6).getQuestion()).startsWith("乙批");
    }

    @Test
    void writeQuizShouldRetryBatchOnceThenFailFast() {
        // 批内失败重试一次（两批垃圾输出 = 1 次调用 + 1 次重试），最终抛 BusinessException 快速失败
        ChatClient chatClient = mock(ChatClient.class, org.mockito.Mockito.RETURNS_DEEP_STUBS);
        when(chatClient.prompt().system(anyString()).user(anyString()).call().content())
                .thenReturn("输出无法解析", "还是无法解析");
        QuizPlanService service = new QuizPlanService();
        org.springframework.test.util.ReflectionTestUtils.setField(service, "generationChatClient", chatClient);

        List<PlanItem> plan = List.of(planItem("知识点甲"), planItem("知识点乙"));

        org.assertj.core.api.Assertions.assertThatThrownBy(
                        () -> service.writeQuiz(plan, List.of(segment(0, 10, "内容")), null, null))
                .isInstanceOf(com.xueji.agent.exception.BusinessException.class)
                .hasMessageContaining("写题失败");
    }

    @Test
    void writeQuizShouldStopImmediatelyOnBalanceInsufficient() {
        // 余额不足：批内不重试，直接透出充值文案
        ChatClient chatClient = mock(ChatClient.class, org.mockito.Mockito.RETURNS_DEEP_STUBS);
        when(chatClient.prompt().system(anyString()).user(anyString()).call().content())
                .thenThrow(new RuntimeException("DeepSeek API returned HTTP 402 Insufficient Balance"));
        QuizPlanService service = new QuizPlanService();
        org.springframework.test.util.ReflectionTestUtils.setField(service, "generationChatClient", chatClient);

        List<PlanItem> plan = List.of(planItem("知识点甲"), planItem("知识点乙"));

        org.assertj.core.api.Assertions.assertThatThrownBy(
                        () -> service.writeQuiz(plan, List.of(segment(0, 10, "内容")), null, null))
                .isInstanceOf(com.xueji.agent.exception.BusinessException.class)
                .hasMessage(com.xueji.agent.common.ModelServiceErrors.BALANCE_MESSAGE);
    }
}
