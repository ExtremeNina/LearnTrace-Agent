package com.xueji.agent.ai.tool;

import com.xueji.agent.ai.ContentSearchService;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;

/**
 * 内容检索工具（B26 阶段 4 Content Tools）：
 * 在用户网课的 ContentDocument（章节 / 知识点）与转写原文中检索，结果带 [mm:ss] 时间戳
 */
public class ContentSearchTool {

    private final ContentSearchService contentSearchService;

    public ContentSearchTool(ContentSearchService contentSearchService) {
        this.contentSearchService = contentSearchService;
    }

    @Tool(description = "在用户的网课内容（章节 / 知识点 / 转写原文）中检索。"
            + "当用户询问某门课或某个视频里讲过什么、某个概念 / 术语出现在第几分钟时调用。"
            + "返回结果带 [mm:ss] 时间戳，回答时应引用这些时间戳方便用户跳回视频")
    public String contentSearch(
            @ToolParam(description = "检索关键词，如概念名 / 术语 / 短语", required = true)
            String keyword,
            ToolContext toolContext) {
        Long userId = (Long) toolContext.getContext().get("userId");
        try {
            return contentSearchService.search(userId, keyword);
        } catch (Exception e) {
            return "SEARCH_FAILED: " + e.getMessage();
        }
    }
}
