package com.xueji.agent.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.xueji.agent.common.OwnershipCheck;
import com.xueji.agent.domain.entity.Note;
import com.xueji.agent.domain.entity.QuestionRecord;
import com.xueji.agent.domain.entity.ReviewCard;
import com.xueji.agent.domain.entity.ReviewLog;
import com.xueji.agent.domain.entity.SimilarQuestion;
import com.xueji.agent.domain.vo.ReviewCardVO;
import com.xueji.agent.exception.BusinessException;
import com.xueji.agent.mapper.NoteMapper;
import com.xueji.agent.mapper.QuestionRecordMapper;
import com.xueji.agent.mapper.ReviewCardMapper;
import com.xueji.agent.mapper.ReviewLogMapper;
import com.xueji.agent.mapper.SimilarQuestionMapper;
import com.xueji.agent.service.ReviewScheduler;
import com.xueji.agent.service.ReviewService;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 复习系统实现：review_card 为统一调度状态（四种来源各一张卡，内容实时组装不复制），
 * 评分走 ReviewScheduler（SM-2 简化版）并写 review_log 流水。
 * 来源实体被删除时由各模块级联调用 removeBySource 移出队列。
 */
@Slf4j
@Service
public class ReviewServiceImpl implements ReviewService {

    public static final String CARD_QUESTION = "question";
    public static final String CARD_SIMILAR = "similar";
    public static final String CARD_NOTE = "note";

    private static final int TODAY_QUEUE_LIMIT = 20;

    @Resource
    private ReviewCardMapper reviewCardMapper;

    @Resource
    private ReviewLogMapper reviewLogMapper;

    @Resource
    private QuestionRecordMapper questionRecordMapper;

    @Resource
    private SimilarQuestionMapper similarQuestionMapper;

    @Resource
    private NoteMapper noteMapper;

    @Override
    public ReviewCardVO addCard(Long userId, String cardType, Long refId) {
        validateType(cardType);
        validateSource(userId, cardType, refId);

        ReviewCard existing = reviewCardMapper.selectOne(new QueryWrapper<ReviewCard>()
                .eq("user_id", userId)
                .eq("card_type", cardType)
                .eq("ref_id", refId));
        if (existing != null) {
            if (Integer.valueOf(1).equals(existing.getDeleted())) {
                // 曾加入后又移出：恢复并重置调度状态
                existing.setDeleted(0)
                        .setDueAt(LocalDateTime.now())
                        .setIntervalDays(0)
                        .setEase(BigDecimal.valueOf(ReviewScheduler.EASE_DEFAULT))
                        .setReps(0)
                        .setLapses(0)
                        .setUpdatedAt(LocalDateTime.now());
                reviewCardMapper.updateById(existing);
                return toVO(existing);
            }
            throw new BusinessException("该内容已在复习队列中");
        }

        ReviewCard card = new ReviewCard()
                .setUserId(userId)
                .setCardType(cardType)
                .setRefId(refId)
                .setDueAt(LocalDateTime.now())
                .setIntervalDays(0)
                .setEase(BigDecimal.valueOf(ReviewScheduler.EASE_DEFAULT))
                .setReps(0)
                .setLapses(0)
                .setDeleted(0)
                .setCreatedAt(LocalDateTime.now())
                .setUpdatedAt(LocalDateTime.now());
        reviewCardMapper.insert(card);
        log.info("已加入复习, userId={}, cardType={}, refId={}, cardId={}", userId, cardType, refId, card.getId());
        return toVO(card);
    }

    @Override
    public List<ReviewCardVO> todayQueue(Long userId) {
        LocalDateTime endOfToday = LocalDate.now().atTime(23, 59, 59);
        List<ReviewCard> cards = reviewCardMapper.selectList(new QueryWrapper<ReviewCard>()
                .eq("user_id", userId)
                .eq("deleted", 0)
                .le("due_at", endOfToday)
                .orderByAsc("due_at")
                .last("LIMIT " + TODAY_QUEUE_LIMIT));
        List<ReviewCardVO> queue = new ArrayList<>();
        for (ReviewCard card : cards) {
            ReviewCardVO vo = assemble(card);
            if (vo != null) {
                queue.add(vo);
            }
        }
        return queue;
    }

    @Override
    public ReviewCardVO review(Long userId, Long cardId, int grade) {
        if (grade < ReviewScheduler.GRADE_AGAIN || grade > ReviewScheduler.GRADE_GOOD) {
            throw new BusinessException("非法评分");
        }
        ReviewCard card = OwnershipCheck.requireOwned(reviewCardMapper.selectById(cardId), userId, "复习卡不存在");
        ReviewScheduler.State state = ReviewScheduler.next(grade,
                card.getIntervalDays(), card.getEase().doubleValue(), card.getReps(), card.getLapses());

        card.setIntervalDays(state.intervalDays())
                .setEase(BigDecimal.valueOf(state.ease()))
                .setReps(state.reps())
                .setLapses(state.lapses())
                .setDueAt(LocalDateTime.now().plusDays(state.intervalDays()))
                .setUpdatedAt(LocalDateTime.now());
        reviewCardMapper.updateById(card);

        ReviewLog reviewLog = new ReviewLog()
                .setUserId(userId)
                .setCardId(cardId)
                .setGrade(grade)
                .setIntervalAfter(state.intervalDays())
                .setReviewedAt(LocalDateTime.now());
        reviewLogMapper.insert(reviewLog);
        return toVO(card);
    }

    @Override
    public Map<String, Object> stats(Long userId) {
        LocalDateTime endOfToday = LocalDate.now().atTime(23, 59, 59);
        LocalDateTime startOfToday = LocalDate.now().atStartOfDay();
        Map<String, Object> stats = new HashMap<>();
        stats.put("dueCount", reviewCardMapper.selectCount(new QueryWrapper<ReviewCard>()
                .eq("user_id", userId)
                .eq("deleted", 0)
                .le("due_at", endOfToday)));
        stats.put("total", reviewCardMapper.selectCount(new QueryWrapper<ReviewCard>()
                .eq("user_id", userId)
                .eq("deleted", 0)));
        stats.put("reviewedToday", reviewLogMapper.selectCount(new QueryWrapper<ReviewLog>()
                .eq("user_id", userId)
                .ge("reviewed_at", startOfToday)));
        return stats;
    }

    @Override
    public void removeCard(Long userId, Long cardId) {
        ReviewCard card = OwnershipCheck.requireOwned(reviewCardMapper.selectById(cardId), userId, "复习卡不存在");
        card.setDeleted(1).setUpdatedAt(LocalDateTime.now());
        reviewCardMapper.updateById(card);
    }

    @Override
    public void removeBySource(Long userId, String cardType, Long refId) {
        reviewCardMapper.update(null, new UpdateWrapper<ReviewCard>()
                .eq("user_id", userId)
                .eq("card_type", cardType)
                .eq("ref_id", refId)
                .set("deleted", 1)
                .set("updated_at", LocalDateTime.now()));
    }

    @Override
    public boolean inQueue(Long userId, String cardType, Long refId) {
        return reviewCardMapper.selectCount(new QueryWrapper<ReviewCard>()
                .eq("user_id", userId)
                .eq("card_type", cardType)
                .eq("ref_id", refId)
                .eq("deleted", 0)) > 0;
    }

    private void validateType(String cardType) {
        if (!CARD_QUESTION.equals(cardType) && !CARD_SIMILAR.equals(cardType) && !CARD_NOTE.equals(cardType)) {
            throw new BusinessException("不支持的卡片类型");
        }
    }

    private void validateSource(Long userId, String cardType, Long refId) {
        switch (cardType) {
            case CARD_QUESTION -> OwnershipCheck.requireOwned(questionRecordMapper.selectById(refId), userId, "题目不存在");
            case CARD_SIMILAR -> OwnershipCheck.requireOwned(similarQuestionMapper.selectById(refId), userId, "题目不存在");
            case CARD_NOTE -> {
                Note note = OwnershipCheck.requireOwned(noteMapper.selectById(refId), userId, "笔记不存在");
                if (note.getNodeType() != null && note.getNodeType() != 0) {
                    throw new BusinessException("分组不能加入复习");
                }
            }
            default -> throw new BusinessException("不支持的卡片类型");
        }
    }

    /**
     * 组装卡片两面：question / similar → 题干与解答；note → 标题与正文。
     * 来源实体已被删除时返回 null（调用方跳过，保持队列干净）
     */
    private ReviewCardVO assemble(ReviewCard card) {
        return switch (card.getCardType()) {
            case CARD_QUESTION -> {
                QuestionRecord record = questionRecordMapper.selectById(card.getRefId());
                if (record == null || Integer.valueOf(1).equals(record.getDeleted())) {
                    yield null;
                }
                yield toVO(card)
                        .setFrontText(record.getQuestionText())
                        .setBackText(record.getCorrectAnswer())
                        .setAnalysis(record.getAnalysis())
                        .setImageUrl(record.getImageOssKey());
            }
            case CARD_SIMILAR -> {
                SimilarQuestion similar = similarQuestionMapper.selectById(card.getRefId());
                if (similar == null || Integer.valueOf(1).equals(similar.getDeleted())) {
                    yield null;
                }
                yield toVO(card)
                        .setFrontText(similar.getQuestionText())
                        .setBackText(similar.getAnswer())
                        .setAnalysis(similar.getAnalysis());
            }
            case CARD_NOTE -> {
                Note note = noteMapper.selectById(card.getRefId());
                if (note == null || Integer.valueOf(1).equals(note.getDeleted())) {
                    yield null;
                }
                yield toVO(card)
                        .setFrontText(note.getTitle())
                        .setBackText(note.getContent());
            }
            default -> null;
        };
    }

    private ReviewCardVO toVO(ReviewCard card) {
        return new ReviewCardVO()
                .setId(card.getId())
                .setCardType(card.getCardType())
                .setRefId(card.getRefId())
                .setDueAt(card.getDueAt())
                .setIntervalDays(card.getIntervalDays())
                .setReps(card.getReps())
                .setLapses(card.getLapses());
    }
}
