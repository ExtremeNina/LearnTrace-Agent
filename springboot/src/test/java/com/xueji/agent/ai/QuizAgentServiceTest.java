package com.xueji.agent.ai;

import com.xueji.agent.ai.QuizAgentService.QuizMaterial;
import com.xueji.agent.ai.QuizAgentService.QuizQuestion;
import com.xueji.agent.domain.entity.Course;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * QuizAgent 纯函数单测：出题 JSON 容错解析、用户消息组装（自适应数量 / 防重复）、对话展示文本
 */
class QuizAgentServiceTest {

    @Test
    void parseShouldReadQuestionsObject() {
        List<QuizQuestion> questions = QuizAgentService.parseQuestions("""
                ```json
                {"count": 4, "questions": [
                  {"question": "洋流中断的根本原因是什么？", "answer": "海水结冰……", "analysis": "材料在 05:00 提到……", "sourceSec": 300},
                  {"question": "什么是过冷水？", "answer": "低于冰点仍保持液态的水", "analysis": "材料在 07:12 提到"},
                  {"question": "", "answer": "无效项"},
                  {"question": "负时间项", "sourceSec": -5}
                ]}
                ```
                """);
        // 无题面项跳过；负时间归位为 null（仍有效）→ 3 条
        assertThat(questions).hasSize(3);
        assertThat(questions.get(0).getSourceSec()).isEqualTo(300);
        assertThat(questions.get(2).getSourceSec()).isNull();
    }

    @Test
    void parseShouldAcceptBareArrayAndRejectGarbage() {
        List<QuizQuestion> arr = QuizAgentService.parseQuestions(
                "[{\"question\": \"裸数组题\", \"answer\": \"A\"}]");
        assertThat(arr).hasSize(1);
        assertThat(QuizAgentService.parseQuestions("抱歉")).isEmpty();
        assertThat(QuizAgentService.parseQuestions(null)).isEmpty();
    }

    @Test
    void quizUserPromptShouldCarryMaterialProfileAndFeedback() {
        Course course = new Course().setTitle("全球冰封");
        QuizMaterial material = new QuizMaterial(null, List.of(),
                List.of(), List.of(), 524);
        String prompt = QuizAgentService.buildQuizUserPrompt(course, material, null, null,
                List.of("已有旧题一"), List.of("第 1 题依据缺失"));
        assertThat(prompt).contains("《全球冰封》").contains("视频时长：524 秒")
                .contains("本次建议出约").contains("不得少于")
                .contains("评审反馈").contains("第 1 题依据缺失")
                .contains("已有旧题一");
        // 无画像（null 或未填写）不出现画像段
        assertThat(prompt).doesNotContain("学习者画像");
    }

    @Test
    void quizUserPromptShouldCarryExplicitCount() {
        Course course = new Course().setTitle("全球冰封");
        QuizMaterial material = new QuizMaterial(null, List.of(), List.of(), List.of(), 524);
        String prompt = QuizAgentService.buildQuizUserPrompt(course, material, 7, null, List.of(), List.of());
        assertThat(prompt).contains("请出 7 道练习题");
    }

    @Test
    void adaptivePromptShouldCarryBaselineWithFloor() {
        Course course = new Course().setTitle("线性代数");
        // 45 分钟视频 → baseline = 2700/240 ≈ 11
        QuizMaterial material = new QuizMaterial(null, List.of(), List.of(), List.of(), 2700);
        String prompt = QuizAgentService.buildQuizUserPrompt(course, material, null, null, List.of(), List.of());
        assertThat(prompt).contains("本次建议出约 11 题").contains("不得少于 9");
        assertThat(QuizAgentService.baselineCount(2700)).isEqualTo(11);
        // 短视频 / 超长视频 clamp
        assertThat(QuizAgentService.baselineCount(120)).isEqualTo(3);
        assertThat(QuizAgentService.baselineCount(0)).isEqualTo(3);
        assertThat(QuizAgentService.baselineCount(7200)).isEqualTo(15);
    }

    @Test
    void formatForChatShouldPreviewWithTimestamps() throws Exception {
        Course course = new Course().setTitle("全球冰封");
        QuizQuestion q = new QuizQuestion();
        setField(q, "question", "洋流中断的原因？");
        setField(q, "sourceSec", 300);
        String text = QuizAgentService.formatForChat(course, List.of(q));
        assertThat(text).contains("《全球冰封》").contains("1. 洋流中断的原因？ [05:00]").contains("练习测验");
    }

    private static void setField(Object target, String name, Object value) throws Exception {
        Field f = target.getClass().getDeclaredField(name);
        f.setAccessible(true);
        f.set(target, value);
    }
}
