package com.xueji.agent.ai;

import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 截断 JSON 修复单测（输出 token 上限导致的尾部断裂）：截断补齐、完整 JSON 返回 null、结构错乱返回 null
 */
class SalvageTruncatedJsonTest {

    @Test
    void shouldRepairTruncatedArrayByDroppingTail() {
        String truncated = "{\"title\":\"t\",\"summary\":\"s\",\"sections\":[{\"title\":\"a\",\"start\":0,\"end\":10}],"
                + "\"knowledgePoints\":[{\"name\":\"p1\",\"time\":5},{\"name\":\"p2\",\"det\"";
        String salvaged = ContentUnderstandingService.salvageTruncatedJson(truncated);

        assertThat(salvaged).isNotNull();
        JSONObject obj = JSONUtil.parseObj(salvaged);
        assertThat(obj.getJSONArray("sections")).hasSize(1);
        // 残缺尾项（p2）被丢弃，p1 完整保留
        assertThat(obj.getJSONArray("knowledgePoints")).hasSize(1);
        assertThat(obj.getJSONArray("knowledgePoints").getJSONObject(0).getStr("name")).isEqualTo("p1");
    }

    @Test
    void shouldRepairTruncatedRootObject() {
        // 根对象自身未闭合
        String truncated = "{\"title\":\"t\",\"summary\":\"s\"";
        String salvaged = ContentUnderstandingService.salvageTruncatedJson(truncated);
        assertThat(salvaged).isNotNull();
        assertThat(JSONUtil.parseObj(salvaged).getStr("title")).isEqualTo("t");
    }

    @Test
    void shouldReturnNullForCompleteJson() {
        String complete = "{\"title\":\"t\",\"points\":[{\"name\":\"p1\"}]}";
        // 无未闭合括号 → 非截断，交回调用方走重试
        assertThat(ContentUnderstandingService.salvageTruncatedJson(complete)).isNull();
    }

    @Test
    void shouldReturnNullForStructuralCorruption() {
        // 括号交错（非单纯截断）
        String corrupted = "{\"title\":\"t\", \"points\": [\"a\"}]}";
        assertThat(ContentUnderstandingService.salvageTruncatedJson(corrupted)).isNull();
    }

    @Test
    void shouldNotBeFooledByBracesInsideStrings() {
        // 字符串字面量里的括号不参与配对，截断点在其后
        String truncated = "{\"summary\":\"函数 f(x) { return x; } 的讲解\",\"points\":[{\"name\":\"p1\"";
        String salvaged = ContentUnderstandingService.salvageTruncatedJson(truncated);
        assertThat(salvaged).isNotNull();
        assertThat(JSONUtil.parseObj(salvaged).getJSONArray("points")).hasSize(1);
    }
}
