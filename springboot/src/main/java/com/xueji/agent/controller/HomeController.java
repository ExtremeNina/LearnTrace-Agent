package com.xueji.agent.controller;

import com.xueji.agent.common.Result;
import com.xueji.agent.domain.dto.StudyHeartbeatDto;
import com.xueji.agent.service.HomeService;
import com.xueji.agent.service.StudyTimeService;
import com.xueji.agent.utils.UserUtils;
import jakarta.annotation.Resource;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 首页仪表盘接口（B25 工单 3 / 学习时长心跳）
 */
@RequestMapping("/home")
@RestController
public class HomeController {

    @Resource
    private HomeService homeService;

    @Resource
    private StudyTimeService studyTimeService;

    /**
     * 首页聚合：问候昵称 + 继续学习 + 最近学习 + 今日复习 + 学习数据 + 本周统计
     */
    @GetMapping("/overview")
    public Result<Map<String, Object>> overview() {
        Long userId = UserUtils.getCurrentLoginId();
        return Result.data(homeService.overview(userId));
    }

    /**
     * 学习时长心跳：前端每 60 秒在站上报一次，累计到今天
     */
    @PostMapping("/study-time")
    public Result<Void> heartbeat(@RequestBody StudyHeartbeatDto dto) {
        Long userId = UserUtils.getCurrentLoginId();
        studyTimeService.heartbeat(userId, dto == null ? null : dto.getSeconds());
        return Result.ok();
    }
}
