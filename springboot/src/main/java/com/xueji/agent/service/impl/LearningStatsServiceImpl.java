package com.xueji.agent.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.xueji.agent.domain.enums.CourseStatus;
import com.xueji.agent.domain.dto.WeakCardInfo;
import com.xueji.agent.domain.entity.Course;
import com.xueji.agent.domain.entity.Note;
import com.xueji.agent.domain.entity.QuestionRecord;
import com.xueji.agent.domain.entity.ReviewCard;
import com.xueji.agent.domain.entity.ReviewLog;
import com.xueji.agent.domain.entity.SimilarQuestion;
import com.xueji.agent.mapper.CourseMapper;
import com.xueji.agent.mapper.NoteMapper;
import com.xueji.agent.mapper.QuestionRecordMapper;
import com.xueji.agent.mapper.ReviewCardMapper;
import com.xueji.agent.mapper.ReviewLogMapper;
import com.xueji.agent.mapper.SimilarQuestionMapper;
import com.xueji.agent.service.LearningStatsService;
import com.xueji.agent.service.ReviewScheduler;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Service;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 学习状态统计实现：全部为确定性查询（无 LLM），
 * 每日简报与 Agent 工具 get_learning_status 共用同一份口径
 */
@Service
public class LearningStatsServiceImpl implements LearningStatsService {

    private static final int MAX_WEAK_CARDS = 5;

    @Resource
    private ReviewCardMapper reviewCardMapper;

    @Resource
    private ReviewLogMapper reviewLogMapper;

    @Resource
    private NoteMapper noteMapper;

    @Resource
    private CourseMapper courseMapper;

    @Resource
    private QuestionRecordMapper questionRecordMapper;

    @Resource
    private SimilarQuestionMapper similarQuestionMapper;

    @Override
    public Map<String, Object> getStatsSnapshot(Long userId) {
        LocalDateTime weekStart = LocalDate.now().with(DayOfWeek.MONDAY).atStartOfDay();
        LocalDateTime endOfToday = LocalDate.now().atTime(23, 59, 59);

        Map<String, Object> stats = new LinkedHashMap<>();
        stats.put("dueToday", reviewCardMapper.selectCount(new QueryWrapper<ReviewCard>()
                .eq("user_id", userId).eq("deleted", 0).le("due_at", endOfToday)));
        stats.put("totalCards", reviewCardMapper.selectCount(new QueryWrapper<ReviewCard>()
                .eq("user_id", userId).eq("deleted", 0)));
        stats.put("reviewedThisWeek", reviewLogMapper.selectCount(new QueryWrapper<ReviewLog>()
                .eq("user_id", userId).ge("reviewed_at", weekStart)));
        stats.put("againThisWeek", reviewLogMapper.selectCount(new QueryWrapper<ReviewLog>()
                .eq("user_id", userId).eq("grade", ReviewScheduler.GRADE_AGAIN).ge("reviewed_at", weekStart)));
        stats.put("notesCreatedThisWeek", noteMapper.selectCount(new QueryWrapper<Note>()
                .eq("user_id", userId).eq("deleted", 0).eq("node_type", 0).ge("created_at", weekStart)));
        stats.put("coursesTotal", courseMapper.selectCount(new QueryWrapper<Course>()
                .eq("user_id", userId).eq("deleted", 0)));
        stats.put("coursesSuccess", courseMapper.selectCount(new QueryWrapper<Course>()
                .eq("user_id", userId).eq("deleted", 0).eq("status", CourseStatus.SUCCESS)));
        stats.put("weakCards", findWeakCards(userId, weekStart));
        return stats;
    }

    /** 本周被评为"生疏"的卡片（最近的在前，上限 5 张）：错题 / 相似题带题干与错因，供 LLM 归纳薄弱主题 */
    private List<WeakCardInfo> findWeakCards(Long userId, LocalDateTime weekStart) {
        List<ReviewLog> againLogs = reviewLogMapper.selectList(new QueryWrapper<ReviewLog>()
                .eq("user_id", userId)
                .eq("grade", ReviewScheduler.GRADE_AGAIN)
                .ge("reviewed_at", weekStart)
                .orderByDesc("reviewed_at"));
        // 同一张卡本周可能生疏多次，按最近一次去重
        Set<Long> seenCardIds = new LinkedHashSet<>();
        for (ReviewLog log : againLogs) {
            seenCardIds.add(log.getCardId());
        }

        List<WeakCardInfo> weakCards = new ArrayList<>();
        for (Long cardId : seenCardIds) {
            if (weakCards.size() >= MAX_WEAK_CARDS) {
                break;
            }
            ReviewCard card = reviewCardMapper.selectById(cardId);
            if (card == null || Integer.valueOf(1).equals(card.getDeleted())) {
                continue;
            }
            WeakCardInfo info = loadWeakCardSource(card);
            if (info != null) {
                weakCards.add(info);
            }
        }
        return weakCards;
    }

    private WeakCardInfo loadWeakCardSource(ReviewCard card) {
        switch (card.getCardType()) {
            case "question" -> {
                QuestionRecord record = questionRecordMapper.selectById(card.getRefId());
                if (record == null || Integer.valueOf(1).equals(record.getDeleted())) {
                    return null;
                }
                return new WeakCardInfo().setCardType("question").setRefId(record.getId())
                        .setFront(record.getQuestionText()).setAnalysis(record.getAnalysis()).setSubject(record.getSubject());
            }
            case "similar" -> {
                SimilarQuestion similar = similarQuestionMapper.selectById(card.getRefId());
                if (similar == null || Integer.valueOf(1).equals(similar.getDeleted())) {
                    return null;
                }
                return new WeakCardInfo().setCardType("similar").setRefId(similar.getId())
                        .setFront(similar.getQuestionText()).setAnalysis(similar.getAnalysis()).setSubject(similar.getSubject());
            }
            case "note" -> {
                Note note = noteMapper.selectById(card.getRefId());
                if (note == null || Integer.valueOf(1).equals(note.getDeleted())) {
                    return null;
                }
                return new WeakCardInfo().setCardType("note").setRefId(note.getId())
                        .setFront(note.getTitle());
            }
            default -> {
                return null;
            }
        }
    }
}
