package com.xueji.agent.controller;

import com.xueji.agent.common.Result;
import com.xueji.agent.domain.vo.BriefingVO;
import com.xueji.agent.service.BriefingService;
import com.xueji.agent.utils.UserUtils;
import jakarta.annotation.Resource;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 每日学习简报接口：惰性生成（当天首次访问触发 LLM，落库缓存幂等）+ 强制刷新
 */
@RequestMapping("/briefing")
@RestController
public class BriefingController {

    @Resource
    private BriefingService briefingService;

    /** 今日简报：当天已有则读缓存，否则聚合统计并生成 */
    @GetMapping("/today")
    public Result<BriefingVO> today() {
        return Result.data(briefingService.getTodayBriefing(UserUtils.getCurrentLoginId()));
    }

    /** 强制刷新今日简报（重新聚合统计并重新生成） */
    @PostMapping("/refresh")
    public Result<BriefingVO> refresh() {
        return Result.data(briefingService.refreshTodayBriefing(UserUtils.getCurrentLoginId()));
    }
}
