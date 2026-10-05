package com.xueji.agent.service.impl;

import com.xueji.agent.domain.vo.SearchResultVO;
import com.xueji.agent.service.SearchService;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.filter.Filter.Expression;
import org.springframework.ai.vectorstore.filter.Filter.ExpressionType;
import org.springframework.ai.vectorstore.filter.Filter.Key;
import org.springframework.ai.vectorstore.filter.Filter.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * 全局搜索实现：与 Agent 工具 rag_search 同一个向量库与 userId 过滤口径，
 * 区别在于返回结构化结果（含来源 ID / 时间戳）供前端分组跳转，而非 LLM 文本
 */
@Slf4j
@Service
public class GlobalSearchServiceImpl implements SearchService {

    private static final int DEFAULT_LIMIT = 8;

    @Resource
    private VectorStore vectorStore;

    @Override
    public List<SearchResultVO> search(Long userId, String query) {
        if (query == null || query.isBlank()) {
            return List.of();
        }
        // userId 硬过滤（只召回本人数据），与 rag_search 相同口径
        Expression filter = new Expression(ExpressionType.EQ, new Key("userId"), new Value(String.valueOf(userId)));
        List<Document> docs = vectorStore.similaritySearch(SearchRequest.builder()
                .query(query)
                .topK(DEFAULT_LIMIT)
                .filterExpression(filter)
                .build());
        List<SearchResultVO> results = new ArrayList<>();
        if (docs == null) {
            return results;
        }
        for (Document doc : docs) {
            SearchResultVO vo = toVo(doc);
            if (vo != null) {
                results.add(vo);
            }
        }
        return results;
    }

    /** 向量文档转结构化结果：来源 ID 从文档 ID 前缀解析（q: / note: / transcript:c），解析失败跳过该条 */
    private SearchResultVO toVo(Document doc) {
        try {
            String type = String.valueOf(doc.getMetadata().getOrDefault("type", "question"));
            String docId = doc.getId();
            SearchResultVO vo = new SearchResultVO().setSnippet(doc.getText());
            if ("note".equals(type)) {
                // 文档 ID 形如 note:{noteId}:{块序号}
                String[] parts = docId.split(":");
                if (parts.length < 2) {
                    return null;
                }
                return vo.setType("note").setRefId(Long.valueOf(parts[1]));
            }
            if ("transcript".equals(type)) {
                // 文档 ID 形如 transcript:c{courseId}:{segmentId}，片段起始秒在元数据
                String[] parts = docId.split(":");
                if (parts.length < 3 || !parts[1].startsWith("c")) {
                    return null;
                }
                vo.setType("transcript").setCourseId(Long.valueOf(parts[1].substring(1)));
                Object tsSec = doc.getMetadata().get("tsSec");
                if (tsSec != null) {
                    vo.setTsSec(Integer.valueOf(String.valueOf(tsSec)));
                }
                return vo;
            }
            // 题目类：仅拍照题目（q: 前缀）外露 ID；相似题（sq:）无法回跳来源，不入结果
            if (!docId.startsWith("q:")) {
                return null;
            }
            return vo.setType("question")
                    .setRefId(Long.valueOf(docId.substring(2)))
                    .setSubject(String.valueOf(doc.getMetadata().getOrDefault("subject", "")))
                    .setWrong("1".equals(String.valueOf(doc.getMetadata().getOrDefault("isWrong", ""))));
        } catch (NumberFormatException e) {
            log.warn("全局搜索结果解析失败, docId={}", doc.getId());
            return null;
        }
    }
}
