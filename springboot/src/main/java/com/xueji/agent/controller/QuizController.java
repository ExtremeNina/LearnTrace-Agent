package com.xueji.agent.controller;

import com.xueji.agent.common.Result;
import com.xueji.agent.domain.vo.QuizPickVO;
import com.xueji.agent.service.QuizService;
import com.xueji.agent.utils.UserUtils;
import jakarta.annotation.Resource;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 练习 / 测验模式接口：从题库随机抽题组卷（无状态，作答与评分在前端本地完成）
 */
@RequestMapping("/quiz")
@RestController
public class QuizController {

    @Resource
    private QuizService quizService;

    /**
     * 组卷抽题
     *
     * @param count   抽题数量（前端 5 / 10 两档）
     * @param subject 学科（可选）
     * @param period  时间段：7d / 30d / all（默认 all）
     * @param sources 来源：question / similar，逗号分隔（默认全部）
     */
    @GetMapping("/pick")
    public Result<List<QuizPickVO>> pick(@RequestParam int count,
                                         @RequestParam(required = false) String subject,
                                         @RequestParam(defaultValue = "all") String period,
                                         @RequestParam(required = false) List<String> sources) {
        return Result.data(quizService.pick(UserUtils.getCurrentLoginId(), count, subject, period, sources));
    }
}
