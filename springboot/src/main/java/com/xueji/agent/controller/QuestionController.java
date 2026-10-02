package com.xueji.agent.controller;

import com.xueji.agent.common.Result;
import com.xueji.agent.domain.dto.QuestionUpdateDto;
import com.xueji.agent.domain.entity.QuestionRecord;
import com.xueji.agent.domain.vo.PageVO;
import com.xueji.agent.service.QuestionService;
import com.xueji.agent.utils.UserUtils;
import jakarta.annotation.Resource;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 题目记录接口：拍照记录分页列表（可按日期筛选）、详情、编辑与删除
 */
@RequestMapping("/question")
@RestController
public class QuestionController {

    @Resource
    private QuestionService questionService;

    /**
     * 当前用户的拍照记录分页列表（新记录在前，每页默认 10 条）
     *
     * @param date    可选，按日期筛选（yyyy-MM-dd）
     * @param subject 可选，按学科筛选
     */
    @GetMapping("/list")
    public Result<PageVO<QuestionRecord>> list(
            @RequestParam(required = false) String date,
            @RequestParam(required = false) String subject,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "10") int size) {
        return Result.data(questionService.listByUser(UserUtils.getCurrentLoginId(), date, subject, page, size));
    }

    /**
     * 题目详情
     */
    @GetMapping("/{id}")
    public Result<QuestionRecord> detail(@PathVariable Long id) {
        return Result.data(questionService.getDetail(UserUtils.getCurrentLoginId(), id));
    }

    /**
     * 编辑题目（仅更新提供的字段）
     */
    @PutMapping("/{id}")
    public Result<QuestionRecord> update(@PathVariable Long id, @RequestBody QuestionUpdateDto dto) {
        return Result.data(questionService.updateByUser(UserUtils.getCurrentLoginId(), id, dto));
    }

    /**
     * 删除题目（逻辑删除）
     */
    @DeleteMapping("/{id}")
    public Result<Void> delete(@PathVariable Long id) {
        questionService.deleteByUser(UserUtils.getCurrentLoginId(), id);
        return Result.ok("已删除");
    }
}
