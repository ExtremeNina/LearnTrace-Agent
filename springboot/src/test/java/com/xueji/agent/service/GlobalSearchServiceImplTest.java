package com.xueji.agent.service;

import com.xueji.agent.domain.vo.SearchResultVO;
import com.xueji.agent.service.impl.GlobalSearchServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.verify;

/**
 * 全局搜索测试：来源 ID 前缀解析（q: / note: / transcript:c）、相似题不入结果、空查询短路
 */
class GlobalSearchServiceImplTest {

    private VectorStore vectorStore;
    private GlobalSearchServiceImpl service;

    @BeforeEach
    void setUp() {
        vectorStore = mock(VectorStore.class);
        service = new GlobalSearchServiceImpl();
        setField("vectorStore", vectorStore);
    }

    private void setField(String name, Object value) {
        try {
            java.lang.reflect.Field f = GlobalSearchServiceImpl.class.getDeclaredField(name);
            f.setAccessible(true);
            f.set(service, value);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

    @Test
    void searchShouldMapAllSourceTypes() {
        when(vectorStore.similaritySearch(any(SearchRequest.class))).thenReturn(List.of(
                new Document("q:15", "勾股定理错题", Map.of("type", "question", "subject", "数学", "isWrong", "1")),
                new Document("note:7:2", "笔记片段", Map.of("type", "note")),
                new Document("transcript:c3:9", "转写片段", Map.of("type", "transcript", "tsSec", 95)),
                new Document("sq:4", "相似题", Map.of("type", "question"))));

        List<SearchResultVO> results = service.search(1L, "勾股");

        assertEquals(3, results.size());
        assertEquals("question", results.get(0).getType());
        assertEquals(15L, results.get(0).getRefId());
        assertEquals("数学", results.get(0).getSubject());
        assertEquals(true, results.get(0).getWrong());
        assertEquals("note", results.get(1).getType());
        assertEquals(7L, results.get(1).getRefId());
        assertEquals("transcript", results.get(2).getType());
        assertEquals(3L, results.get(2).getCourseId());
        assertEquals(95, results.get(2).getTsSec());
        // userId 硬过滤进入向量检索
        verify(vectorStore).similaritySearch(any(SearchRequest.class));
    }

    @Test
    void searchShouldSkipMalformedDocIds() {
        when(vectorStore.similaritySearch(any(SearchRequest.class))).thenReturn(List.of(
                new Document("note:oops", "坏数据", Map.of("type", "note")),
                new Document("q:abc", "坏ID", Map.of("type", "question"))));

        List<SearchResultVO> results = service.search(1L, "任意");

        assertTrue(results.isEmpty());
    }

    @Test
    void searchShouldShortCircuitBlankQuery() {
        assertTrue(service.search(1L, "  ").isEmpty());
        verify(vectorStore, times(0)).similaritySearch(any(SearchRequest.class));
    }
}
