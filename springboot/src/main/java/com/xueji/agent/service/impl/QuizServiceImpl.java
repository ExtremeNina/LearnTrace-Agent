package com.xueji.agent.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.xueji.agent.domain.entity.QuestionRecord;
import com.xueji.agent.domain.entity.SimilarQuestion;
import com.xueji.agent.domain.vo.QuizPickVO;
import com.xueji.agent.exception.BusinessException;
import com.xueji.agent.mapper.QuestionRecordMapper;
import com.xueji.agent.mapper.SimilarQuestionMapper;
import com.xueji.agent.service.QuizService;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;

/**
 * 练习 / 测验模式实现：不建会话表，组卷为无状态随机抽题。
 * 题库 = question_record（拍照题目）+ similar_question（AI 相似题），内容实时组装不复制；
 * 作答在前端本地进行，错题沉淀走 ReviewService.addCardsBatch 批量加卡。
 */
@Slf4j
@Service
public class QuizServiceImpl implements QuizService {

    public static final String SOURCE_QUESTION = "question";
    public static final String SOURCE_SIMILAR = "similar";

    /** 单次抽题上限（前端固定 5 / 10 两档，接口层面放宽到 20 兜底） */
    private static final int MAX_COUNT = 20;
    /** 每个来源最多参与的候选题数（ORDER BY RAND() 的池子上限，防全表扫描） */
    private static final int POOL_LIMIT_PER_SOURCE = 200;

    @Resource
    private QuestionRecordMapper questionRecordMapper;

    @Resource
    private SimilarQuestionMapper similarQuestionMapper;

    @Override
    public List<QuizPickVO> pick(Long userId, int count, String subject, String period, List<String> sources) {
        if (count < 1 || count > MAX_COUNT) {
            throw new BusinessException("每次抽题数量为 1~" + MAX_COUNT + " 题");
        }
        List<String> validSources = normalizeSources(sources);

        List<QuizPickVO> pool = new ArrayList<>();
        if (validSources.contains(SOURCE_QUESTION)) {
            for (QuestionRecord record : questionRecordMapper.selectList(questionPoolWrapper(userId, subject, period))) {
                pool.add(fromQuestion(record));
            }
        }
        if (validSources.contains(SOURCE_SIMILAR)) {
            for (SimilarQuestion similar : similarQuestionMapper.selectList(similarPoolWrapper(userId, subject, period))) {
                pool.add(fromSimilar(similar));
            }
        }
        if (pool.isEmpty()) {
            throw new BusinessException("题库中没有符合条件的题目，先去积累一些题目吧");
        }
        List<QuizPickVO> picked = shufflePick(pool, count, new Random());
        log.info("练习组卷完成, userId={}, count={}, subject={}, period={}, sources={}",
                userId, picked.size(), subject, period, validSources);
        return picked;
    }

    /**
     * 纯函数：洗牌后取前 count 道（count 超过池子大小时返回全部），供单测
     */
    static List<QuizPickVO> shufflePick(List<QuizPickVO> pool, int count, Random random) {
        List<QuizPickVO> copied = new ArrayList<>(pool);
        Collections.shuffle(copied, random);
        int take = Math.min(count, copied.size());
        List<QuizPickVO> result = new ArrayList<>(take);
        for (int i = 0; i < take; i++) {
            result.add(copied.get(i));
        }
        return result;
    }

    /** 来源白名单过滤，空则默认全部 */
    private List<String> normalizeSources(List<String> sources) {
        List<String> valid = new ArrayList<>();
        if (sources != null) {
            for (String source : sources) {
                if (SOURCE_QUESTION.equals(source) || SOURCE_SIMILAR.equals(source)) {
                    valid.add(source);
                }
            }
        }
        if (valid.isEmpty()) {
            valid.add(SOURCE_QUESTION);
            valid.add(SOURCE_SIMILAR);
        }
        return valid;
    }

    private QueryWrapper<QuestionRecord> questionPoolWrapper(Long userId, String subject, String period) {
        QueryWrapper<QuestionRecord> wrapper = new QueryWrapper<QuestionRecord>()
                .eq("user_id", userId)
                .eq("deleted", 0)
                .isNotNull("question_text")
                .ne("question_text", "");
        applyCommonFilters(wrapper, subject, period);
        return wrapper;
    }

    private QueryWrapper<SimilarQuestion> similarPoolWrapper(Long userId, String subject, String period) {
        QueryWrapper<SimilarQuestion> wrapper = new QueryWrapper<SimilarQuestion>()
                .eq("user_id", userId)
                .eq("deleted", 0)
                .isNotNull("question_text")
                .ne("question_text", "");
        applyCommonFilters(wrapper, subject, period);
        return wrapper;
    }

    /** 学科 / 时间段过滤 + 随机取样上限（两表列名一致，泛型复用） */
    private <T> void applyCommonFilters(QueryWrapper<T> wrapper, String subject, String period) {
        if (subject != null && !subject.isBlank()) {
            wrapper.eq("subject", subject);
        }
        LocalDateTime cutoff = periodCutoff(period);
        if (cutoff != null) {
            wrapper.ge("created_at", cutoff);
        }
        wrapper.last("ORDER BY RAND() LIMIT " + POOL_LIMIT_PER_SOURCE);
    }

    /** 时间段解析：7d / 30d 返回起点，其余（含 all）返回 null 表示不限 */
    private LocalDateTime periodCutoff(String period) {
        if ("7d".equals(period)) {
            return LocalDateTime.now().minusDays(7);
        }
        if ("30d".equals(period)) {
            return LocalDateTime.now().minusDays(30);
        }
        return null;
    }

    private QuizPickVO fromQuestion(QuestionRecord record) {
        return new QuizPickVO()
                .setCardType(SOURCE_QUESTION)
                .setRefId(record.getId())
                .setQuestionText(record.getQuestionText())
                .setImageUrl(record.getImageOssKey())
                .setCorrectAnswer(record.getCorrectAnswer())
                .setAnalysis(record.getAnalysis())
                .setSubject(record.getSubject())
                .setCreatedAt(record.getCreatedAt());
    }

    private QuizPickVO fromSimilar(SimilarQuestion similar) {
        return new QuizPickVO()
                .setCardType(SOURCE_SIMILAR)
                .setRefId(similar.getId())
                .setQuestionText(similar.getQuestionText())
                .setCorrectAnswer(similar.getAnswer())
                .setAnalysis(similar.getAnalysis())
                .setSubject(similar.getSubject())
                .setCreatedAt(similar.getCreatedAt());
    }
}
