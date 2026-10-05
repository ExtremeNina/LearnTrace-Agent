package com.xueji.agent.controller;

import com.xueji.agent.common.Result;
import com.xueji.agent.domain.vo.SearchResultVO;
import com.xueji.agent.service.SearchService;
import com.xueji.agent.utils.UserUtils;
import jakarta.annotation.Resource;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 全局搜索接口（B15）：顶栏搜索框 / Ctrl+K
 */
@RequestMapping("/search")
@RestController
public class SearchController {

    @Resource
    private SearchService searchService;

    /**
     * 语义搜索本人的题目 / 笔记 / 网课转写片段
     */
    @GetMapping
    public Result<List<SearchResultVO>> search(@RequestParam("q") String q) {
        Long userId = UserUtils.getCurrentLoginId();
        return Result.data(searchService.search(userId, q));
    }
}
