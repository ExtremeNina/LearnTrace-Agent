package com.xueji.agent.controller;

import com.xueji.agent.common.Result;
import com.xueji.agent.domain.dto.AddReviewCardDto;
import com.xueji.agent.domain.dto.AddReviewCardsDto;
import com.xueji.agent.domain.dto.ReviewGradeDto;
import com.xueji.agent.domain.vo.ReviewBatchAddVO;
import com.xueji.agent.domain.vo.ReviewCardVO;
import com.xueji.agent.service.ReviewService;
import com.xueji.agent.utils.UserUtils;
import jakarta.annotation.Resource;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 复习系统接口：加卡 / 今日队列 / 评分 / 统计 / 移出 / 状态查询
 */
@RequestMapping("/review")
@RestController
public class ReviewController {

    @Resource
    private ReviewService reviewService;

    /** 统计：今日待复习 / 队列总数 / 今日已复习 */
    @GetMapping("/stats")
    public Result<Map<String, Object>> stats() {
        return Result.data(reviewService.stats(UserUtils.getCurrentLoginId()));
    }

    /** 今日队列（到期卡，按到期排序，上限 20） */
    @GetMapping("/today")
    public Result<List<ReviewCardVO>> today() {
        return Result.data(reviewService.todayQueue(UserUtils.getCurrentLoginId()));
    }

    /** 加入复习 */
    @PostMapping("/cards")
    public Result<ReviewCardVO> addCard(@RequestBody AddReviewCardDto dto) {
        return Result.data(reviewService.addCard(UserUtils.getCurrentLoginId(), dto.getCardType(), dto.getRefId()));
    }

    /** 批量加入复习（练习模式一键入队：已在队列 / 无效项跳过不中断） */
    @PostMapping("/cards/batch")
    public Result<ReviewBatchAddVO> addCardsBatch(@RequestBody AddReviewCardsDto dto) {
        return Result.data(reviewService.addCardsBatch(UserUtils.getCurrentLoginId(), dto.getItems()));
    }

    /** 是否已在复习队列 */
    @GetMapping("/status")
    public Result<Boolean> status(@RequestParam String cardType, @RequestParam Long refId) {
        return Result.data(reviewService.inQueue(UserUtils.getCurrentLoginId(), cardType, refId));
    }

    /** 复习评分（0 生疏 / 1 模糊 / 2 熟练） */
    @PostMapping("/cards/{id}/review")
    public Result<ReviewCardVO> review(@PathVariable Long id, @RequestBody ReviewGradeDto dto) {
        return Result.data(reviewService.review(UserUtils.getCurrentLoginId(), id, dto.getGrade()));
    }

    /** 移出复习队列 */
    @DeleteMapping("/cards/{id}")
    public Result<Void> removeCard(@PathVariable Long id) {
        reviewService.removeCard(UserUtils.getCurrentLoginId(), id);
        return Result.ok("已移出复习队列");
    }
}
