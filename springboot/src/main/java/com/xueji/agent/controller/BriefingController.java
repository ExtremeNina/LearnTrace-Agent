package com.xueji.agent.controller;

import com.xueji.agent.common.Result;
import com.xueji.agent.domain.vo.BriefingVO;
import com.xueji.agent.service.BriefingService;
import com.xueji.agent.utils.UserUtils;
import jakarta.annotation.Resource;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 每日学习简报接口：按（用户 × 会话 × 日期）惰性生成（落库缓存幂等）+ 强制刷新 + 按会话查询
 */
@RequestMapping("/briefing")
@RestController
public class BriefingController {

    @Resource
    private BriefingService briefingService;

    /** 今日简报：按（用户 × 会话 × 日期）已有则读缓存，否则聚合统计并生成（conversationId 可空） */
    @GetMapping("/today")
    public Result<BriefingVO> today(@RequestParam(value = "conversationId", required = false) Long conversationId) {
        return Result.data(briefingService.getTodayBriefing(UserUtils.getCurrentLoginId(), conversationId));
    }

    /** 强制刷新今日简报（重新聚合统计并重新生成，conversationId 可空） */
    @PostMapping("/refresh")
    public Result<BriefingVO> refresh(@RequestParam(value = "conversationId", required = false) Long conversationId) {
        return Result.data(briefingService.refreshTodayBriefing(UserUtils.getCurrentLoginId(), conversationId));
    }

    /** 查询某会话的全部简报（按生成时间升序），供切换会话时恢复简报在消息流中的位置 */
    @GetMapping("/conversation/{conversationId}")
    public Result<List<BriefingVO>> listByConversation(@PathVariable Long conversationId) {
        return Result.data(briefingService.listByConversation(UserUtils.getCurrentLoginId(), conversationId));
    }
}
