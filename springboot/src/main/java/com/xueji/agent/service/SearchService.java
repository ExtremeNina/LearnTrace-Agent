package com.xueji.agent.service;

import com.xueji.agent.domain.vo.SearchResultVO;

import java.util.List;

/**
 * 全局语义搜索（B15）：跨题目 / 笔记 / 网课转写检索本人学习片段
 */
public interface SearchService {

    /**
     * 语义检索：userId 硬过滤只召回本人数据，返回结构化结果（前端按来源分组跳转）
     */
    List<SearchResultVO> search(Long userId, String query);
}
