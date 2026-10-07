package com.xueji.agent.service;

import com.xueji.agent.ai.ContentUnderstanding;
import com.xueji.agent.domain.entity.Course;
import com.xueji.agent.domain.entity.ContentDocument;
import com.xueji.agent.domain.entity.ContentKnowledgePoint;
import com.xueji.agent.domain.entity.ContentSection;

import java.util.List;

/**
 * ContentDocument 语义层持久化（B26 阶段 2）：理解结果三表落库 / 查询 / 重生成清理。
 * Raw Transcript 永不覆盖；ContentDocument 可重生成（保存前先删旧数据）
 */
public interface ContentDocumentService {

    /** 保存理解结果（幂等：先删该课程旧数据再插），返回 document ID */
    Long save(Course courseRef, ContentUnderstanding understanding);

    /** 查课程当前 ContentDocument（无则 NULL） */
    ContentDocument findByCourse(Long courseId);

    /** 章节列表（按 sort 升序） */
    List<ContentSection> listSections(Long documentId);

    /** 知识点列表（按 sort 升序） */
    List<ContentKnowledgePoint> listKnowledgePoints(Long documentId);

    /** 删除课程的 ContentDocument 三表数据（重生成前调用） */
    void deleteByCourse(Long courseId);
}
