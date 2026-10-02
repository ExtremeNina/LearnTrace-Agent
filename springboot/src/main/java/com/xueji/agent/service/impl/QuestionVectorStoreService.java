package com.xueji.agent.service.impl;

import com.xueji.agent.domain.entity.QuestionRecord;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadPoolExecutor;

/**
 * 题目向量库：把保存成功的题目切片向量化入库，供 rag_search 检索生成相似题。
 * 向量化失败不阻塞保存主流程（该题只是搜不到），由日志告警；题目编辑 / 删除时同步向量库。
 */
@Slf4j
@Service
public class QuestionVectorStoreService {

    /** 向量化文本组装：题干 + 我的作答 + 错因（不含解答全文；剥离 Markdown 修饰符但保留公式 $） */
    public static String buildText(QuestionRecord record) {
        StringBuilder sb = new StringBuilder();
        sb.append("题目：").append(stripMarks(record.getQuestionText()));
        if (record.getUserAnswer() != null && !record.getUserAnswer().isBlank()) {
            sb.append("\n我的作答：").append(stripMarks(record.getUserAnswer()));
        }
        if (record.getAnalysis() != null && !record.getAnalysis().isBlank()) {
            sb.append("\n错因：").append(stripMarks(record.getAnalysis()));
        }
        return sb.toString();
    }

    private static String stripMarks(String s) {
        return s == null ? "" : s.replaceAll("[#*`]", " ").replaceAll("\\s+", " ").trim();
    }

    @Resource
    private VectorStore vectorStore;

    @Resource(name = "courseExecutor")
    private ThreadPoolExecutor courseExecutor;

    /** 异步入库：embedding 为远程调用，放线程池避免拖慢对话回合 */
    public void ingestAsync(QuestionRecord record) {
        courseExecutor.execute(() -> {
            try {
                ingest(record);
            } catch (Exception e) {
                log.warn("题目向量化入库失败, questionId={}", record.getId(), e);
            }
        });
    }

    /** 删除对应向量（题目删除 / 内容修改重建时调用） */
    public void remove(Long questionId) {
        try {
            vectorStore.delete(List.of(String.valueOf(questionId)));
        } catch (Exception e) {
            log.warn("题目向量删除失败, questionId={}", questionId, e);
        }
    }

    void ingest(QuestionRecord record) {
        String text = buildText(record);
        if (text.isBlank()) {
            return;
        }
        Document document = new Document(String.valueOf(record.getId()), text, Map.of(
                "userId", String.valueOf(record.getUserId()),
                "subject", record.getSubject() == null || record.getSubject().isBlank() ? "未分类" : record.getSubject(),
                "isWrong", record.getIsWrong() == null ? "未判定" : String.valueOf(record.getIsWrong())));
        vectorStore.add(List.of(document));
        log.info("题目已向量化入库, questionId={}", record.getId());
    }
}
