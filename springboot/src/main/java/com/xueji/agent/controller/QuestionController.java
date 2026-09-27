package com.xueji.agent.controller;

import cn.dev33.satoken.stp.StpUtil;
import com.xueji.agent.common.Result;
import com.xueji.agent.domain.entity.QuestionRecord;
import com.xueji.agent.service.QuestionService;
import jakarta.annotation.Resource;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 题目记录接口：拍照记录列表与题目详情
 */
@RequestMapping("/question")
@RestController
public class QuestionController {

    @Resource
    private QuestionService questionService;

    /**
     * 当前用户的拍照记录列表（新记录在前）
     */
    @GetMapping("/list")
    public Result<List<QuestionRecord>> list() {
        return Result.data(questionService.listByUser(StpUtil.getLoginIdAsLong()));
    }

    /**
     * 题目详情
     */
    @GetMapping("/{id}")
    public Result<QuestionRecord> detail(@PathVariable Long id) {
        return Result.data(questionService.getDetail(StpUtil.getLoginIdAsLong(), id));
    }
}
