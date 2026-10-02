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
        stubSearch(new Document("1", "题目：一元二次方程", Map.of("type", "question", "isWrong", "1", "subject", "数学")));

        String output = tool().ragSearch("一元二次方程", "", CONTEXT);

        assertThat(output).contains("第1条【题目 · 数学 · 错题】");
        assertThat(output).contains("题目：一元二次方程");
    }

    @Test
    void correctAnswerMetadataShouldNotMark() {
        stubSearch(
                new Document("2", "题目：三角函数", Map.of("type", "question", "isWrong", "0", "subject", "数学")),
                new Document("3", "题目：数列", Map.of("type", "question", "isWrong", "未判定", "subject", "数学")));

        String output = tool().ragSearch("数列", "", CONTEXT);

        assertThat(output).doesNotContain("错题");
        assertThat(output).contains("第1条【题目 · 数学】");
        assertThat(output).contains("第2条【题目 · 数学】");
        assertThat(output).contains("---");
    }

    @Test
    void missingMetadataShouldFallBackToDefaults() {
        stubSearch(new Document("4", "题目：概率初步", Map.of()));

        String output = tool().ragSearch("概率", "", CONTEXT);

        // 无 type 的旧文档按题目处理；subject 缺省"未分类"，isWrong 缺省不标错题
        assertThat(output).contains("第1条【题目 · 未分类】");
        assertThat(output).doesNotContain("错题");
    }

    @Test
    void noteAndTranscriptDocsShouldUseTypeLabels() {
        stubSearch(
                new Document("5", "笔记《罗尔定理》\n若函数满足三条件，则存在驻点。", Map.of("type", "note", "userId", "5")),
                new Document("6", "网课《计算机科学 第 3 讲》[04:18-05:17]\n今天讲罗尔定理。",
                        Map.of("type", "transcript", "userId", "5", "courseId", "20", "tsSec", "258")));

        String output = tool().ragSearch("罗尔定理", "", CONTEXT);

        assertThat(output).contains("第1条【笔记】");
        assertThat(output).contains("第2条【网课】");
        assertThat(output).contains("[04:18-05:17]");
        assertThat(output).doesNotContain("错题");
    }

    @Test
    void questionDocsWithPrefixShouldExposeQuestionIdOnlyForQ() {
        stubSearch(
                // 拍照题目（q: 前缀）外露题目 ID，供保存相似题时回填 sourceQuestionId
                new Document("q:7", "题目：一元二次方程", Map.of("type", "question", "isWrong", "1", "subject", "数学")),
                // 相似题（sq: 前缀）的 ID 不能作为来源题目，不外露
                new Document("sq:3", "题目：数列极限", Map.of("type", "question", "isWrong", "0", "subject", "数学")));

        String output = tool().ragSearch("方程", "", CONTEXT);

        assertThat(output).contains("第1条【题目 · 数学 · 错题 · 题目ID 7】");
        assertThat(output).contains("第2条【题目 · 数学】");
        assertThat(output).doesNotContain("sq:3");
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
