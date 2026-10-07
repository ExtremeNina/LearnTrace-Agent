package com.xueji.agent.ai;

import com.xueji.agent.ai.QuizAgentService.QuizQuestion;
import com.xueji.agent.domain.entity.Course;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * QuizAgent 纯函数单测（B26 阶段 4）：出题 JSON 容错解析、用户消息组装、对话展示文本
 */
class QuizAgentServiceTest {

    @Test
    void parseShouldReadQuestionsObject() {
        List<QuizQuestion> questions = QuizAgentService.parseQuestions("""
                ```json
                {"questions": [
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
        String prompt = QuizAgentService.buildQuizUserPrompt(course, null, List.of(), List.of(),
                "[00:40] 冰封样本", 5, null, List.of("第 1 题依据缺失"));
        assertThat(prompt).contains("《全球冰封》").contains("[00:40] 冰封样本")
                .contains("出 5 道练习题").contains("评审反馈").contains("第 1 题依据缺失");
        // 无画像（null 或未填写）不出现画像段
        assertThat(prompt).doesNotContain("学习者画像");
    }

    @Test
    void formatForChatShouldPreviewWithTimestamps() {
        Course course = new Course().setTitle("全球冰封");
        QuizQuestion q = new QuizQuestion();
        try {
            java.lang.reflect.Field f = QuizQuestion.class.getDeclaredField("question");
            f.setAccessible(true);
            f.set(q, "洋流中断的原因？");
            f = QuizQuestion.class.getDeclaredField("sourceSec");
            f.setAccessible(true);
            f.set(q, 300);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
        String text = QuizAgentService.formatForChat(course, List.of(q));
        assertThat(text).contains("《全球冰封》").contains("1. 洋流中断的原因？ [05:00]").contains("练习测验");
    }
}
