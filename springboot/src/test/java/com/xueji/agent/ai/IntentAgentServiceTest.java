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

    @Test
    void titleFromFileNameShouldStripUploadPrefixes() {
        // 原始文件名原样保留（去扩展名）
        assertThat(IntentAgentService.titleFromFileName(java.nio.file.Path.of("x/线性代数第5讲-矩阵乘法.mp4")))
                .isEqualTo("线性代数第5讲-矩阵乘法");
        // xj-chat-video-<时间戳>- 前缀剥离（上传临时文件名）
        assertThat(IntentAgentService.titleFromFileName(java.nio.file.Path.of("xj-chat-video-1728382712-我的视频.mp4")))
                .isEqualTo("我的视频");
        // UUID 前缀（8+ 位十六进制-）剥离
        assertThat(IntentAgentService.titleFromFileName(java.nio.file.Path.of("a1b2c3d4-如果全球冰封.mp4")))
                .isEqualTo("如果全球冰封");
        // 尾部 -<10 位以上时间戳> 序号剥离
        assertThat(IntentAgentService.titleFromFileName(java.nio.file.Path.of("如果全球冰封-1728382712.mp4")))
                .isEqualTo("如果全球冰封");
        // 全部被剥离干净 → 兜底「未命名课程」
        assertThat(IntentAgentService.titleFromFileName(java.nio.file.Path.of("xj-chat-video-1728382712-.mp4")))
                .isEqualTo("未命名课程");
    }
}
