package com.xueji.agent.service;

import com.xueji.agent.domain.entity.Course;
import com.xueji.agent.domain.entity.CourseQuizQuestion;
import com.xueji.agent.domain.entity.CourseTranscriptSegment;

import java.util.List;

/**
 * 课程课后习题编排（B26 习题产物化）：
 * 流水线末端 / 重新生成自动出题、追加出题、详情查询、沉淀（题目管理 / 复习计划）
 */
public interface CourseQuizService {

    /** 全量重新出题（三明治质检：先删旧再落库），返回题数；LLM 失败抛异常由调用方兜底 */
    int regenerateForCourse(Course course, List<CourseTranscriptSegment> transcript);

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
