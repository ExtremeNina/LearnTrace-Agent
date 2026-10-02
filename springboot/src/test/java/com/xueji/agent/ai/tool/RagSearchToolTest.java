package com.xueji.agent.ai.tool;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * RAG 检索工具：isWrong 元数据单类型（字符串）比较、错题标记、空结果与失败降级
 */
@ExtendWith(MockitoExtension.class)
class RagSearchToolTest {

    private static final ToolContext CONTEXT = new ToolContext(Map.of("userId", 5L));

    @Mock
    private VectorStore vectorStore;

    private RagSearchTool tool() {
        return new RagSearchTool(vectorStore);
    }

    private void stubSearch(Document... docs) {
        when(vectorStore.similaritySearch(any(SearchRequest.class))).thenReturn(List.of(docs));
    }

    @Test
    void wrongAnswerMetadataShouldMarkAsWrong() {
        stubSearch(new Document("1", "题目：一元二次方程", Map.of("isWrong", "1", "subject", "数学")));

        String output = tool().ragSearch("一元二次方程", "", CONTEXT);

        assertThat(output).contains("第1条【数学 · 错题】");
        assertThat(output).contains("题目：一元二次方程");
    }

    @Test
    void correctAnswerMetadataShouldNotMark() {
        stubSearch(
                new Document("2", "题目：三角函数", Map.of("isWrong", "0", "subject", "数学")),
                new Document("3", "题目：数列", Map.of("isWrong", "未判定", "subject", "数学")));

        String output = tool().ragSearch("数列", "", CONTEXT);

        assertThat(output).doesNotContain("错题");
        assertThat(output).contains("第1条【数学】");
        assertThat(output).contains("第2条【数学】");
        assertThat(output).contains("---");
    }

    @Test
    void missingMetadataShouldFallBackToDefaults() {
        stubSearch(new Document("4", "题目：概率初步", Map.of()));

        String output = tool().ragSearch("概率", "", CONTEXT);

        // subject 缺省"未分类"，isWrong 缺省不标错题
        assertThat(output).contains("第1条【未分类】");
        assertThat(output).doesNotContain("错题");
    }

    @Test
    void emptyResultShouldReturnFriendlyText() {
        stubSearch();

        String output = tool().ragSearch("力学", "", CONTEXT);

        assertThat(output).isEqualTo("未检索到相关学习片段");
    }

    @Test
    void searchFailureShouldReturnFailureMarker() {
        when(vectorStore.similaritySearch(any(SearchRequest.class))).thenThrow(new IllegalStateException("redis down"));

        String output = tool().ragSearch("力学", "", CONTEXT);

        assertThat(output).isEqualTo("RAG_SEARCH_FAILED");
    }
}
