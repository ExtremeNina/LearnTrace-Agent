package com.xueji.agent.ai;

import com.xueji.agent.ai.IntentAgentService.IntentResult;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 意图 Agent 纯函数单测（B27）：意图解析 JSON 容错（剥围栏 / 找大括号 / 非法输出）
 */
class IntentAgentServiceTest {

    @Test
    void parseIntentShouldReadAllFields() {
        IntentResult result = IntentAgentService.parseIntent("""
                ```json
                {"intent": "COURSE", "gradeLevel": "高三", "goal": "期末备考", "note": "多讲例题",
                 "reply": "好的，按完整网课处理。"}
                ```
                """);
        assertThat(result).isNotNull();
        assertThat(result.intent).isEqualTo("COURSE");
        assertThat(result.gradeLevel).isEqualTo("高三");
        assertThat(result.goal).isEqualTo("期末备考");
        assertThat(result.note).isEqualTo("多讲例题");
        assertThat(result.reply).contains("完整网课处理");
    }

    @Test
    void parseIntentShouldNormalizeIntentCase() {
        IntentResult result = IntentAgentService.parseIntent(
                "{\"intent\": \"transcribe\", \"gradeLevel\": null, \"goal\": null, \"note\": null, \"reply\": \"ok\"}");
        assertThat(result).isNotNull();
        assertThat(result.intent).isEqualTo("TRANSCRIBE");
    }

    @Test
    void parseIntentShouldTolerateGarbage() {
        assertThat(IntentAgentService.parseIntent("我觉得用户想做课程")).isNull();
        assertThat(IntentAgentService.parseIntent(null)).isNull();
        assertThat(IntentAgentService.parseIntent("{\"plan\": []}")).isNull();
    }
}
