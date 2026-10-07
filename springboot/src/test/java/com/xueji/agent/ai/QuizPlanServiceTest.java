package com.xueji.agent.ai;

import com.xueji.agent.ai.QuizPlanService.PlanItem;
import com.xueji.agent.domain.entity.CourseTranscriptSegment;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

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
    void planPromptShouldCarryTranscriptProfileAndFeedback() {
        String prompt = QuizPlanService.buildPlanPrompt(
                List.of(segment(0, 300, "线性代数的本质是线性变换。")), null, 300, "数量过少");
        assertThat(prompt).contains("视频时长：300 秒")
                .contains("[0-300s] 线性代数的本质是线性变换。")
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
}
