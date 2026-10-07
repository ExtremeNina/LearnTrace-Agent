package com.xueji.agent.controller;

import com.xueji.agent.common.Result;
import com.xueji.agent.service.CourseQuizService;
import com.xueji.agent.utils.UserUtils;
import jakarta.annotation.Resource;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 课程课后习题（B26 习题产物化）：追加出题 / 沉淀入题目管理与复习计划
 */
@RestController
@RequestMapping("/course-quiz")
public class CourseQuizController {

    @Resource
    private CourseQuizService courseQuizService;

    /** 加入题目管理（复制入 question_record） */
    @PostMapping("/{id}/to-questions")
    public Result<Long> toQuestions(@PathVariable Long id) {
        return Result.data(courseQuizService.toQuestionManagement(UserUtils.getCurrentLoginId(), id));
    }

    /** 加入复习计划（自动先入题目管理，再加复习队列） */
    @PostMapping("/{id}/to-review")
    public Result<Long> toReview(@PathVariable Long id) {
        return Result.data(courseQuizService.toReview(UserUtils.getCurrentLoginId(), id));
    }
}
