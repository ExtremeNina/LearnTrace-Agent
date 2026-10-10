package com.xueji.agent.ai;

import com.xueji.agent.ai.ContentReviewService.Role;
import com.xueji.agent.ai.ContentReviewService.Verdict;
import com.xueji.agent.domain.entity.UserProfile;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 多角色评审纯函数单测（B26 阶段 3）：角色 × 维度矩阵、画像文本、verdict JSON 容错解析
 */
class ContentReviewServiceTest {

    @Test
    void dimensionMatrixShouldCoverThreeRoles() {
        Map<Role, java.util.List<String>> matrix = ContentReviewService.dimensionMatrix();
        assertThat(matrix).containsKeys(Role.CONTENT_DESIGN, Role.EXPLAIN, Role.PRACTICE);
        for (java.util.List<String> dimensions : matrix.values()) {
            assertThat(dimensions).isNotEmpty();
            for (String dimension : dimensions) {
                assertThat(dimension).isNotBlank();
            }
        }
        // 每个角色的维度都能渲染为文本
        for (Role role : matrix.keySet()) {
            assertThat(ContentReviewService.dimensionsText(role)).contains("- ");
        }
    }

    @Test
    void profileTextShouldFallbackWhenEmpty() {
        assertThat(ContentReviewService.profileText(null)).contains("未提供");
        assertThat(ContentReviewService.profileText(new UserProfile())).contains("未提供");

        UserProfile profile = new UserProfile()
                .setGradeLevel("高中").setLevel("入门").setGoal("期末备考").setNote("几何薄弱");
        String text = ContentReviewService.profileText(profile);
        assertThat(text).contains("高中").contains("入门").contains("期末备考").contains("几何薄弱");
    }

    @Test
    void parseShouldReadVerdictJson() {
        Verdict pass = ContentReviewService.parseVerdict("""
                ```json
                {"verdict": "PASS", "score": 88, "issues": ["第 2 章时间戳偏移"]}
                ```
                """);
        assertThat(pass.getVerdict()).isEqualTo("PASS");
        assertThat(pass.isRevise()).isFalse();
        assertThat(pass.getScore()).isEqualTo(88);
        assertThat(pass.getIssues()).containsExactly("第 2 章时间戳偏移");

        Verdict revise = ContentReviewService.parseVerdict(
                "{\"verdict\": \"REVISE\", \"issues\": [\"事实错误：洋流方向\", \"\", \"知识点遗漏\"]}");
        assertThat(revise.isRevise()).isTrue();
        // 空白 issue 被过滤
        assertThat(revise.getIssues()).hasSize(2);
    }

    @Test
    void parseShouldRejectIllegalVerdictAndInvalidJson() {
        // verdict 非法值保守按 REVISE
        Verdict unknown = ContentReviewService.parseVerdict("{\"verdict\": \"MAYBE\"}");
        assertThat(unknown.isRevise()).isTrue();
        // verdict 缺省按 REVISE
        assertThat(ContentReviewService.parseVerdict("{\"issues\": []}").isRevise()).isTrue();
        // 非 JSON / 空输入抛异常
        assertThatThrownBy(() -> ContentReviewService.parseVerdict("我无法评审")).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> ContentReviewService.parseVerdict(null)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void quizReviewPromptShouldCarryMaterialForVerification() {
        String withMaterial = ContentReviewService.buildQuizReviewPrompt(
                "1. 题面\n", null, "[00:00] 四级翻译题主要考查中国的历史文化内容");
        // 有材料：依据标注真实性可逐条对照核实，并附材料段
        assertThat(withMaterial).contains("可逐条对照核实")
                .contains("[评审材料（笔记/转写节选，用于核实依据标注）]")
                .contains("四级翻译题主要考查中国的历史文化内容");
        // 无材料（null / 空白）：引导评审不要仅因无法核实而 REVISE，不附材料段
        String withoutMaterial = ContentReviewService.buildQuizReviewPrompt("1. 题面\n", null, null);
        assertThat(withoutMaterial).contains("不要仅因无法核实时间戳而 REVISE")
                .doesNotContain("[评审材料");
        String blankMaterial = ContentReviewService.buildQuizReviewPrompt("1. 题面\n", null, "  ");
        assertThat(blankMaterial).contains("不要仅因无法核实时间戳而 REVISE").doesNotContain("[评审材料");
        // 维度段始终完整
        assertThat(withMaterial).contains("题目自含性");
    }
}
