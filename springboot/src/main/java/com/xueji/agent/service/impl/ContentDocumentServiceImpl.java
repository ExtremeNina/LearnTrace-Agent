package com.xueji.agent.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.xueji.agent.ai.ContentUnderstanding;
import com.xueji.agent.domain.entity.Course;
import com.xueji.agent.domain.entity.ContentDocument;
import com.xueji.agent.domain.entity.ContentKnowledgePoint;
import com.xueji.agent.domain.entity.ContentSection;
import com.xueji.agent.mapper.ContentDocumentMapper;
import com.xueji.agent.mapper.ContentKnowledgePointMapper;
import com.xueji.agent.mapper.ContentSectionMapper;
import com.xueji.agent.service.ContentDocumentService;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

/**
 * ContentDocument 持久化实现（B26 阶段 2）
 */
@Slf4j
@Service
public class ContentDocumentServiceImpl implements ContentDocumentService {

    @Resource
    private ContentDocumentMapper documentMapper;

    @Resource
    private ContentSectionMapper sectionMapper;

    @Resource
    private ContentKnowledgePointMapper knowledgePointMapper;

    @Override
    @Transactional
    public Long save(Course courseRef, ContentUnderstanding understanding) {
        deleteByCourse(courseRef.getId());

        ContentDocument document = new ContentDocument()
                .setCourseId(courseRef.getId())
                .setUserId(courseRef.getUserId())
                .setTitle(understanding.getTitle())
                .setSummary(understanding.getSummary())
                .setModelConfigId(courseRef.getModelConfigId())
                .setCreatedAt(LocalDateTime.now())
                .setUpdatedAt(LocalDateTime.now());
        documentMapper.insert(document);

        int sort = 0;
        for (ContentUnderstanding.Section section : understanding.getSections()) {
            sectionMapper.insert(new ContentSection()
                    .setDocumentId(document.getId())
                    .setTitle(section.getTitle())
                    .setSummary(section.getSummary())
                    .setStartSec(section.getStartSec())
                    .setEndSec(section.getEndSec())
                    .setSort(sort++));
        }

        sort = 0;
        for (ContentUnderstanding.KnowledgePoint point : understanding.getKnowledgePoints()) {
            knowledgePointMapper.insert(new ContentKnowledgePoint()
                    .setDocumentId(document.getId())
                    .setName(point.getName())
                    .setDetail(point.getDetail())
                    .setTimeSec(point.getTimeSec())
                    .setSectionSort(point.getSectionSort())
                    .setImportant(Boolean.TRUE.equals(point.getImportant()) ? 1 : 0)
                    .setErrorProne(Boolean.TRUE.equals(point.getErrorProne()) ? 1 : 0)
                    .setSort(sort++));
        }
        log.info("ContentDocument 已保存, courseId={}, 章节={}, 知识点={}",
                courseRef.getId(), understanding.getSections().size(), understanding.getKnowledgePoints().size());
        return document.getId();
    }

    @Override
    public ContentDocument findByCourse(Long courseId) {
        return documentMapper.selectOne(new QueryWrapper<ContentDocument>()
                .eq("course_id", courseId)
                .orderByDesc("id")
                .last("LIMIT 1"));
    }

    @Override
    public List<ContentSection> listSections(Long documentId) {
        return sectionMapper.selectList(new QueryWrapper<ContentSection>()
                .eq("document_id", documentId)
                .orderByAsc("sort"));
    }

    @Override
    public List<ContentKnowledgePoint> listKnowledgePoints(Long documentId) {
        return knowledgePointMapper.selectList(new QueryWrapper<ContentKnowledgePoint>()
                .eq("document_id", documentId)
                .orderByAsc("sort"));
    }

    @Override
    @Transactional
    public void deleteByCourse(Long courseId) {
        ContentDocument document = findByCourse(courseId);
        if (document == null) {
            return;
        }
        knowledgePointMapper.delete(new QueryWrapper<ContentKnowledgePoint>().eq("document_id", document.getId()));
        sectionMapper.delete(new QueryWrapper<ContentSection>().eq("document_id", document.getId()));
        documentMapper.deleteById(document.getId());
    }
}
