package com.xueji.agent.service.impl;

import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.xueji.agent.ai.RagIngestService;
import com.xueji.agent.common.OwnershipCheck;
import com.xueji.agent.domain.dto.QuestionUpdateDto;
import com.xueji.agent.domain.entity.Message;
import com.xueji.agent.domain.entity.QuestionRecord;
import com.xueji.agent.domain.entity.SimilarQuestion;
import com.xueji.agent.domain.vo.PageVO;
import com.xueji.agent.domain.vo.QuestionItemVO;
import com.xueji.agent.mapper.ConversationMapper;
import com.xueji.agent.mapper.MessageMapper;
import com.xueji.agent.mapper.QuestionRecordMapper;
import com.xueji.agent.mapper.SimilarQuestionMapper;
import com.xueji.agent.service.QuestionService;
import com.xueji.agent.service.ReviewService;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 题目记录实现：拍照题目（question_record，带图）与 AI 相似题（similar_question，纯文字）双表存储，
 * 列表 / 详情 / 编辑 / 删除按 source 分派，列表页两表合并展示（相似题标注「AI 生成」）。
 * 保存时 imageUrl 从 message 表 payload 确定性获取，题目 / 解答 / 错因内容由模型整理后传入。
 */
@Slf4j
@Service
public class QuestionServiceImpl implements QuestionService {

    /** 题目来源：会话内拍照识别 */
    private static final String SOURCE_PHOTO = "photo";

    @Resource
    private QuestionRecordMapper questionRecordMapper;

    @Resource
    private SimilarQuestionMapper similarQuestionMapper;

    @Resource
    private ConversationMapper conversationMapper;

    @Resource
    private MessageMapper messageMapper;

    @Resource
    private QuestionVectorStoreService questionVectorStoreService;

    @Resource
    private RagIngestService ragIngestService;

    @Resource
    private ReviewService reviewService;

    @Override
    public boolean saveFromConversation(Long userId, Long conversationId, String source, Long sourceQuestionId,
                                        String questionText, String correctAnswer, String analysis, String subject) {
        if (questionText == null || questionText.isBlank()) {
            return false;
        }
        OwnershipCheck.requireOwned(conversationMapper.selectById(conversationId), userId, "会话不存在");

        if (!SOURCE_PHOTO.equals(source)) {
            // 纯文字题目（AI 生成的相似题 / 手打题）入 similar_question 表，前端列表合并展示并标注「AI 生成」
            SimilarQuestion similar = new SimilarQuestion()
                    .setUserId(userId)
                    .setSourceQuestionId(sourceQuestionId)
                    .setQuestionText(questionText)
                    .setAnswer(correctAnswer)
                    .setAnalysis(analysis)
                    .setSubject(subject == null || subject.isBlank() ? null : subject.trim())
                    .setConversationId(conversationId)
                    .setCreatedAt(LocalDateTime.now())
                    .setUpdatedAt(LocalDateTime.now());
            similarQuestionMapper.insert(similar);
            // 向量化入库（异步，失败不阻塞保存），RAG 可再次召回
            ragIngestService.ingestSimilarQuestionAsync(similar);
            log.info("相似题已保存, userId={}, conversationId={}, similarId={}, sourceQuestionId={}",
                    userId, conversationId, similar.getId(), sourceQuestionId);
            return true;
        }

        // 拍照题目的图片取最近一条带图用户消息（payload 内含 imageUrl）
        List<Message> questionMessages = messageMapper.selectList(new QueryWrapper<Message>()
                .eq("conversation_id", conversationId)
                .eq("role", "user")
                .isNotNull("payload")
                .like("payload", "imageUrl")
                .orderByDesc("id")
                .last("LIMIT 1"));
        if (questionMessages.isEmpty()) {
            return false;
        }
        String imageUrl = parsePayload(questionMessages.get(0).getPayload()).getStr("imageUrl", "");

        QuestionRecord record = new QuestionRecord()
                .setUserId(userId)
                .setImageOssKey(imageUrl)
                .setQuestionText(questionText)
                .setSubject(subject == null || subject.isBlank() ? null : subject.trim())
                .setCorrectAnswer(correctAnswer)
                .setAnalysis(analysis)
                .setAiStatus("SUCCESS")
                .setRecordStatus("SAVED")
                .setCreatedAt(LocalDateTime.now())
                .setUpdatedAt(LocalDateTime.now());
        questionRecordMapper.insert(record);
        // 向量化入库（异步，失败不阻塞保存）
        questionVectorStoreService.ingestAsync(record);
        log.info("题目已保存, userId={}, conversationId={}, recordId={}", userId, conversationId, record.getId());
        return true;
    }

    @Override
    public PageVO<QuestionItemVO> listByUser(Long userId, String date, String subject, String keyword, int page, int size) {
        long total = questionRecordMapper.countMerged(userId, date, subject, keyword);
        int safePage = Math.max(page, 1);
        int safeSize = Math.max(size, 1);
        List<QuestionItemVO> list = questionRecordMapper.listMerged(userId, date, subject, keyword,
                safeSize, (long) (safePage - 1) * safeSize);
        return new PageVO<>(list, total, safePage, safeSize);
    }

    @Override
    public QuestionItemVO getDetail(Long userId, Long id, String source) {
        if (!SOURCE_PHOTO.equals(source)) {
            return toVO(ownedSimilar(userId, id));
        }
        return toVO(ownedRecord(userId, id));
    }

    @Override
    public QuestionItemVO updateByUser(Long userId, Long id, String source, QuestionUpdateDto dto) {
        if (!SOURCE_PHOTO.equals(source)) {
            SimilarQuestion similar = ownedSimilar(userId, id);
            if (dto.getQuestionText() != null) {
                similar.setQuestionText(dto.getQuestionText());
            }
            if (dto.getCorrectAnswer() != null) {
                similar.setAnswer(dto.getCorrectAnswer());
            }
            if (dto.getAnalysis() != null) {
                similar.setAnalysis(dto.getAnalysis());
            }
            if (dto.getSubject() != null) {
                similar.setSubject(dto.getSubject().isBlank() ? null : dto.getSubject().trim());
            }
            similar.setUpdatedAt(LocalDateTime.now());
            similarQuestionMapper.updateById(similar);
            // 内容有修改：重建向量
            ragIngestService.ingestSimilarQuestionAsync(similar);
            return toVO(similar);
        }

        QuestionRecord record = ownedRecord(userId, id);
        if (dto.getQuestionText() != null) {
            record.setQuestionText(dto.getQuestionText());
        }
        if (dto.getUserAnswer() != null) {
            record.setUserAnswer(dto.getUserAnswer());
        }
        if (dto.getCorrectAnswer() != null) {
            record.setCorrectAnswer(dto.getCorrectAnswer());
        }
        if (dto.getUserNote() != null) {
            record.setUserNote(dto.getUserNote());
        }
        if (dto.getSubject() != null) {
            record.setSubject(dto.getSubject().isBlank() ? null : dto.getSubject().trim());
        }
        record.setUpdatedAt(LocalDateTime.now());
        questionRecordMapper.updateById(record);
        // 内容有修改：同步重建向量
        questionVectorStoreService.ingestAsync(record);
        return toVO(record);
    }

    @Override
    public void deleteByUser(Long userId, Long id, String source) {
        if (!SOURCE_PHOTO.equals(source)) {
            SimilarQuestion similar = ownedSimilar(userId, id);
            similar.setDeleted(1);
            similar.setUpdatedAt(LocalDateTime.now());
            similarQuestionMapper.updateById(similar);
            ragIngestService.removeSimilar(id);
            reviewService.removeBySource(userId, "similar", id);
            return;
        }

        QuestionRecord record = ownedRecord(userId, id);
        record.setDeleted(1);
        record.setUpdatedAt(LocalDateTime.now());
        questionRecordMapper.updateById(record);
        questionVectorStoreService.remove(id);
        reviewService.removeBySource(userId, "question", id);
    }

    /**
     * 取本人且未删除的拍照题目，否则视为不存在
     */
    private QuestionRecord ownedRecord(Long userId, Long id) {
        return OwnershipCheck.requireOwned(questionRecordMapper.selectById(id), userId, "题目不存在");
    }

    /**
     * 取本人且未删除的相似题，否则视为不存在
     */
    private SimilarQuestion ownedSimilar(Long userId, Long id) {
        return OwnershipCheck.requireOwned(similarQuestionMapper.selectById(id), userId, "题目不存在");
    }

    private QuestionItemVO toVO(QuestionRecord record) {
        return new QuestionItemVO()
                .setId(record.getId())
                .setSource(QuestionItemVO.SOURCE_PHOTO)
                .setQuestionText(record.getQuestionText())
                .setSubject(record.getSubject())
                .setImageOssKey(record.getImageOssKey())
                .setCorrectAnswer(record.getCorrectAnswer())
                .setAnalysis(record.getAnalysis())
                .setIsWrong(record.getIsWrong())
                .setUserAnswer(record.getUserAnswer())
                .setUserNote(record.getUserNote())
                .setCreatedAt(record.getCreatedAt())
                .setUpdatedAt(record.getUpdatedAt());
    }

    private QuestionItemVO toVO(SimilarQuestion similar) {
        return new QuestionItemVO()
                .setId(similar.getId())
                .setSource(QuestionItemVO.SOURCE_SIMILAR_AI)
                .setQuestionText(similar.getQuestionText())
                .setSubject(similar.getSubject())
                .setCorrectAnswer(similar.getAnswer())
                .setAnalysis(similar.getAnalysis())
                .setCreatedAt(similar.getCreatedAt())
                .setUpdatedAt(similar.getUpdatedAt());
    }

    /**
     * 解析消息 payload JSON，异常时返回空对象
     */
    private JSONObject parsePayload(String payload) {
        if (payload == null || payload.isBlank()) {
            return new JSONObject();
        }
        try {
            return JSONUtil.parseObj(payload);
        } catch (Exception e) {
            log.warn("payload 解析失败: {}", payload);
            return new JSONObject();
        }
    }
}
