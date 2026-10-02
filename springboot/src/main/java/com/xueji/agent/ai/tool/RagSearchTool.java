package com.xueji.agent.ai.tool;

import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.document.Document;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;

import org.springframework.ai.vectorstore.filter.Filter.Expression;
import org.springframework.ai.vectorstore.filter.Filter.ExpressionType;
import org.springframework.ai.vectorstore.filter.Filter.Key;
import org.springframework.ai.vectorstore.filter.Filter.Value;

import java.util.List;

/**
 * RAG 检索工具：从向量库召回用户自己的学习片段（做过的题目及错因），
 * 供 LLM 生成相似题或回答学习状态类问题。userId 经 ToolContext 传入，只召回本人数据。
 */
@Slf4j
public class RagSearchTool {

    private static final int DEFAULT_TOP_K = 5;

    private static final int MAX_TOP_K = 10;

    private final VectorStore vectorStore;

    public RagSearchTool(VectorStore vectorStore) {
        this.vectorStore = vectorStore;
    }

    @Tool(name = "rag_search", description = "检索用户自己的学习资料（做过的题目与错因、笔记 / 知识点页、网课转写片段）。在用户要求生成相似题 / 练习题、询问自己的薄弱知识点、询问最近学习内容、或需要引用用户笔记与网课内容回答时调用")
    public String ragSearch(
            @ToolParam(description = "检索查询文本，概括要出题的知识点或主题") String query,
            @ToolParam(description = "学科过滤（如 数学 / 英语）；不确定时留空。仅对题目类资料生效", required = false) String subject,
            ToolContext toolContext) {
        Long userId = ((Number) toolContext.getContext().get("userId")).longValue();
        try {
            // userId 硬过滤（只召回本人数据），可选叠加学科过滤
            Expression filter = new Expression(ExpressionType.EQ, new Key("userId"), new Value(String.valueOf(userId)));
            if (subject != null && !subject.isBlank()) {
                filter = new Expression(ExpressionType.AND, filter,
                        new Expression(ExpressionType.EQ, new Key("subject"), new Value(subject.trim())));
            }
            List<Document> docs = vectorStore.similaritySearch(SearchRequest.builder()
                    .query(query)
                    .topK(DEFAULT_TOP_K)
                    .filterExpression(filter)
                    .build());
            if (docs == null || docs.isEmpty()) {
                return "未检索到相关学习片段";
            }
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < docs.size(); i++) {
                Document doc = docs.get(i);
                // 元数据统一按字符串写入，读取同样收口为字符串再比较
                String type = String.valueOf(doc.getMetadata().getOrDefault("type", "question"));
                String label;
                switch (type) {
                    case "note" -> label = "笔记";
                    case "transcript" -> label = "网课";
                    default -> {
                        Object subjectMeta = doc.getMetadata().getOrDefault("subject", "未分类");
                        String isWrong = String.valueOf(doc.getMetadata().getOrDefault("isWrong", ""));
                        label = "题目 · " + subjectMeta + ("1".equals(isWrong) ? " · 错题" : "");
                    }
                }
                sb.append("第").append(i + 1).append("条【").append(label).append("】\n")
                        .append(doc.getText()).append('\n');
                if (i < docs.size() - 1) {
                    sb.append("---\n");
                }
            }
            return sb.toString();
        } catch (Exception e) {
            log.error("rag_search 执行失败, userId={}", userId, e);
            return "RAG_SEARCH_FAILED";
        }
    }
}
