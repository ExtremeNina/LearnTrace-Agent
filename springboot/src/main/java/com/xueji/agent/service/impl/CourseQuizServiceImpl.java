package com.xueji.agent.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.xueji.agent.ai.QuizAgentService;
import com.xueji.agent.ai.QuizAgentService.QuizQuestion;
import com.xueji.agent.common.OwnershipCheck;
import com.xueji.agent.domain.entity.Course;
import com.xueji.agent.domain.entity.CourseQuizQuestion;
import com.xueji.agent.domain.entity.CourseTranscriptSegment;
import com.xueji.agent.domain.entity.QuestionRecord;
import com.xueji.agent.mapper.CourseMapper;
import com.xueji.agent.mapper.CourseQuizQuestionMapper;
import com.xueji.agent.mapper.CourseTranscriptSegmentMapper;
import com.xueji.agent.mapper.QuestionRecordMapper;
import com.xueji.agent.service.CourseQuizService;
import com.xueji.agent.service.ProfileService;
import com.xueji.agent.service.ReviewService;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * 课程课后习题编排实现（B26 习题产物化）
 */
@Slf4j
@Service
public class CourseQuizServiceImpl implements CourseQuizService {

    @Resource
    private QuizAgentService quizAgentService;

    @Resource
    private CourseQuizQuestionMapper quizQuestionMapper;

    @Resource
    private CourseMapper courseMapper;

    @Resource
    private CourseTranscriptSegmentMapper transcriptMapper;

    @Resource
    private QuestionRecordMapper questionRecordMapper;

    @Resource
    private ProfileService profileService;

    @Resource
    private ReviewService reviewService;

    @Resource(name = "courseExecutor")
    private java.util.concurrent.ThreadPoolExecutor courseExecutor;

    @Override
    public void saveQuizQuestions(Course course, List<QuizQuestion> questions) {
        quizQuestionMapper.delete(new QueryWrapper<CourseQuizQuestion>().eq("course_id", course.getId()));
        int sort = 0;
        for (QuizQuestion q : questions) {
            quizQuestionMapper.insert(new CourseQuizQuestion()
                    .setCourseId(course.getId())
                    .setUserId(course.getUserId())
                    .setQuestionText(q.getQuestion())
                    .setAnswer(q.getAnswer())
                    .setAnalysis(q.getAnalysis())
                    .setSourceSec(q.getSourceSec())
                    .setSort(sort++)
                    .setCreatedAt(LocalDateTime.now()));
        }
        log.info("课程课后习题已落库, courseId={}, 题数={}", course.getId(), questions.size());
    }

    @Override
    public int appendForCourse(Long userId, Long courseId, int count) {
        Course course = courseMapper.selectById(courseId);
        OwnershipCheck.requireOwned(course, userId, "网课不存在");
        List<CourseTranscriptSegment> transcript = transcriptMapper.selectList(
                new QueryWrapper<CourseTranscriptSegment>().eq("course_id", courseId).orderByAsc("sort"));
        List<CourseQuizQuestion> existing = listByCourse(courseId);
        List<String> existingTexts = new ArrayList<>();
        for (CourseQuizQuestion question : existing) {
            existingTexts.add(question.getQuestionText());
        }
        var material = quizAgentService.loadMaterial(course);
        var profile = profileService.getByUser(course.getUserId());
        List<QuizQuestion> questions = quizAgentService.produceQuestions(
                course, material, profile, count < 1 ? 5 : Math.min(count, 15), existingTexts);
        int sort = existingTexts.size();
        for (QuizQuestion q : questions) {
            quizQuestionMapper.insert(new CourseQuizQuestion()
                    .setCourseId(course.getId())
                    .setUserId(course.getUserId())
                    .setQuestionText(q.getQuestion())
                    .setAnswer(q.getAnswer())
                    .setAnalysis(q.getAnalysis())
                    .setSourceSec(q.getSourceSec())
                    .setSort(sort++)
                    .setCreatedAt(LocalDateTime.now()));
        }
        return questions.size();
    }

    @Override
    public void appendAsync(Long userId, Long courseId, int count) {
        courseExecutor.execute(() -> {
            try {
                int added = appendForCourse(userId, courseId, count);
                log.info("课后习题追加完成, courseId={}, 新增={}", courseId, added);
            } catch (Exception e) {
                log.warn("课后习题追加失败, courseId={}", courseId, e);
            }
        });
    }

    @Override
    public List<CourseQuizQuestion> listByCourse(Long courseId) {
        return quizQuestionMapper.selectList(new QueryWrapper<CourseQuizQuestion>()
                .eq("course_id", courseId)
                .orderByAsc("sort"));
    }

    @Override
    public Long toQuestionManagement(Long userId, Long quizQuestionId) {
        CourseQuizQuestion quizQuestion = quizQuestionMapper.selectById(quizQuestionId);
        OwnershipCheck.requireOwned(quizQuestion, userId, "习题不存在");
        Course course = courseMapper.selectById(quizQuestion.getCourseId());

        QuestionRecord record = new QuestionRecord()
                .setUserId(userId)
                .setQuestionText(quizQuestion.getQuestionText())
                .setCorrectAnswer(quizQuestion.getAnswer())
                .setAnalysis(quizQuestion.getAnalysis())
                .setSubject(course == null ? null : course.getSubject())
                .setAiStatus("SUCCESS")
                .setRecordStatus("SAVED")
                .setDeleted(0)
                .setCreatedAt(LocalDateTime.now())
                .setUpdatedAt(LocalDateTime.now());
        questionRecordMapper.insert(record);
        log.info("课后习题已加入题目管理, userId={}, quizQuestionId={}, recordId={}", userId, quizQuestionId, record.getId());
        return record.getId();
    }

    @Override
    public Long toReview(Long userId, Long quizQuestionId) {
        // 复习卡片的 question 类型指向 question_record：先沉淀入题目管理再加复习队列
        Long recordId = toQuestionManagement(userId, quizQuestionId);
        return reviewService.addCard(userId, "question", recordId).getId();
    }
}
