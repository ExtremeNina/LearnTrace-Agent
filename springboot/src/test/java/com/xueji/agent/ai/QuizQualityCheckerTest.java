package com.xueji.agent.ai;

import com.xueji.agent.ai.QuizAgentService.QuizQuestion;
import com.xueji.agent.ai.QuizQualityChecker.Result;
import com.xueji.agent.domain.entity.ContentKnowledgePoint;
import com.xueji.agent.domain.entity.CourseTranscriptSegment;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 课后习题代码闸单测（三明治质检）：幻觉词面覆盖率剔除 / 依据越界 / 重复检测 / 结构完整
 */
class QuizQualityCheckerTest {

    private CourseTranscriptSegment segment(int start, int end, String text) {
        return new CourseTranscriptSegment().setStartSec(start).setEndSec(end).setText(text).setSort(0);
    }

    private QuizQuestion question(String text, String answer, Integer sourceSec) {
        QuizQuestion q = new QuizQuestion();
        try {
            java.lang.reflect.Field f = QuizQuestion.class.getDeclaredField("question");
            f.setAccessible(true);
            f.set(q, text);
            f = QuizQuestion.class.getDeclaredField("answer");
            f.setAccessible(true);
            f.set(q, answer);
            f = QuizQuestion.class.getDeclaredField("analysis");
            f.setAccessible(true);
            f.set(q, "解析");
            f = QuizQuestion.class.getDeclaredField("sourceSec");
            f.setAccessible(true);
            f.set(q, sourceSec);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
        return q;
    }

    private List<CourseTranscriptSegment> material() {
        return List.of(
                segment(0, 300, "线性代数的本质是线性变换，矩阵表示基向量的去向。行列式是变换前后面积的比值。"),
                segment(300, 600, "特征向量在变换中只被拉伸不旋转，特征值是拉伸比例，通过行列式为零求解。"));
    }

    @Test
    void shouldKeepQuestionGroundedInMaterial() {
        Result result = QuizQualityChecker.check(
                List.of(question("矩阵表示的是什么？请结合基向量说明。", "基向量的去向", 100)),
                material(), List.of(), 600, List.of());
        assertThat(result.getKept()).hasSize(1);
        assertThat(result.getRemovedReasons()).isEmpty();
    }

    @Test
    void shouldRejectHallucinatedQuestionOffMaterial() {
        // 题面词汇与材料完全无关（量子纠缠 / 光速 / 薛定谔）
        Result result = QuizQualityChecker.check(
                List.of(question("请说明量子纠缠与光速不变原理在薛定谔方程中的关联。", "略", 100)),
                material(), List.of(), 600, List.of());
        assertThat(result.getKept()).isEmpty();
        assertThat(result.getRemovedReasons().get(0)).contains("脱离材料");
    }

    @Test
    void shouldRejectSourceSecOutOfRange() {
        Result result = QuizQualityChecker.check(
                List.of(question("行列式是变换前后面积的比值，这个说法对吗？", "对", 5000)),
                material(), List.of(), 600, List.of());
        assertThat(result.getKept()).isEmpty();
        assertThat(result.getRemovedReasons().get(0)).contains("越界");
    }

    @Test
    void shouldRejectDuplicateAgainstExisting() {
        String existing = "矩阵表示的是什么？请结合基向量说明它的含义。";
        Result result = QuizQualityChecker.check(
                List.of(question("矩阵表示的是什么？请结合基向量说明它的用途。", "基向量的去向", 100)),
                material(), List.of(), 600, List.of(existing));
        assertThat(result.getKept()).isEmpty();
        assertThat(result.getRemovedReasons().get(0)).contains("重复");
    }

    @Test
    void shouldRejectMissingAnswer() {
        Result result = QuizQualityChecker.check(
                List.of(question("行列式是什么？", "", 100)),
                material(), List.of(), 600, List.of());
        assertThat(result.getKept()).isEmpty();
        assertThat(result.getRemovedReasons().get(0)).contains("缺参考答案");
    }

    @Test
    void knowledgePointNamesShouldCountAsMaterial() {
        ContentKnowledgePoint point = new ContentKnowledgePoint().setName("逆流交换系统").setDetail("企鹅腿部保温机制");
        Result result = QuizQualityChecker.check(
                List.of(question("逆流交换系统是如何工作的？它有什么作用？", "动脉静脉缠绕换热", 300)),
                List.of(), List.of(point), 600, List.of());
        assertThat(result.getKept()).hasSize(1);
    }
}
