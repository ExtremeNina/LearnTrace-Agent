package com.xueji.agent.controller;

import com.xueji.agent.common.Result;
import com.xueji.agent.domain.dto.QuestionUpdateDto;
import com.xueji.agent.domain.vo.PageVO;
import com.xueji.agent.domain.vo.QuestionItemVO;
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
 * 题目记录接口：拍照题目与 AI 相似题合并分页列表（可按日期 / 学科筛选）、详情、编辑与删除。
 * 相似题记录带 source=similar_ai 标记，详情 / 编辑 / 删除按 source 分派到对应表
 */
@RequestMapping("/question")
@RestController
public class QuestionController {

    @Resource
    private QuestionService questionService;

    /**
     * 当前用户的题目合并分页列表（新记录在前，每页默认 10 条）
     *
     * @param date    可选，按日期筛选（yyyy-MM-dd）
     * @param subject 可选，按学科筛选
     * @param keyword 可选，按题干关键词模糊搜索
     */
    @GetMapping("/list")
    public Result<PageVO<QuestionItemVO>> list(
            @RequestParam(required = false) String date,
            @RequestParam(required = false) String subject,
            @RequestParam(required = false) String keyword,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "10") int size) {
        return Result.data(questionService.listByUser(UserUtils.getCurrentLoginId(), date, subject, keyword, page, size));
    }

    /**
     * 题目详情
     *
     * @param source photo（缺省）/ similar_ai
     */
    @GetMapping("/{id}")
    public Result<QuestionItemVO> detail(@PathVariable Long id,
                                         @RequestParam(required = false, defaultValue = "photo") String source) {
        return Result.data(questionService.getDetail(UserUtils.getCurrentLoginId(), id, source));
    }

    /**
     * 编辑题目（仅更新提供的字段）
     */
    @PutMapping("/{id}")
    public Result<QuestionItemVO> update(@PathVariable Long id,
                                         @RequestParam(required = false, defaultValue = "photo") String source,
                                         @RequestBody QuestionUpdateDto dto) {
        return Result.data(questionService.updateByUser(UserUtils.getCurrentLoginId(), id, source, dto));
    }

    /**
     * 删除题目（逻辑删除）
     */
    @DeleteMapping("/{id}")
    public Result<Void> delete(@PathVariable Long id,
                               @RequestParam(required = false, defaultValue = "photo") String source) {
        questionService.deleteByUser(UserUtils.getCurrentLoginId(), id, source);
        return Result.ok("已删除");
    }
}
