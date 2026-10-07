package com.xueji.agent.service;

import com.xueji.agent.ai.QuizAgentService.QuizQuestion;
import com.xueji.agent.domain.entity.Course;
import com.xueji.agent.domain.entity.CourseQuizQuestion;

import java.util.List;

/**
 * 课程课后习题编排（B26 习题产物化）：
 * 落库（Graph 判题节点在判题通过后调用）、追加出题、详情查询、沉淀（题目管理 / 复习计划）
 */
public interface CourseQuizService {

    /** 落库课程课后习题（先删旧再插；由 Graph 判题节点在判题通过后调用） */
    void saveQuizQuestions(Course course, List<QuizQuestion> questions);

    /** 追加出题（带已有题防重复），返回新增题数 */
    int appendForCourse(Long userId, Long courseId, int count);

    /** 追加出题异步版（courseExecutor，单次 LLM + 判题约 1 分钟） */
    void appendAsync(Long userId, Long courseId, int count);

    /** 课程习题列表（sort 升序） */
    List<CourseQuizQuestion> listByCourse(Long courseId);

    /** 加入题目管理（复制入 question_record），返回题目记录 ID */
    Long toQuestionManagement(Long userId, Long quizQuestionId);

    /** 加入复习计划（自动先入题目管理，再加复习队列），返回复习卡片 ID */
    Long toReview(Long userId, Long quizQuestionId);
}
