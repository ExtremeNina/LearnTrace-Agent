package com.xueji.agent.controller;

import com.xueji.agent.common.Result;
import com.xueji.agent.domain.entity.Conversation;
import com.xueji.agent.domain.entity.Message;
import com.xueji.agent.service.ConversationService;
import com.xueji.agent.utils.UserUtils;
import jakarta.annotation.Resource;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 会话接口：增删改查
 */
@RequestMapping("/conversations")
@RestController
public class ConversationController {

    @Resource
    private ConversationService conversationService;

    /**
     * 创建会话（标题可选，默认"新对话"）
     */
    @PostMapping
    public Result<Conversation> create(@RequestBody(required = false) Map<String, String> body) {
        Long userId = UserUtils.getCurrentLoginId();
        String title = body == null ? null : body.get("title");
        return Result.data(conversationService.create(userId, title));
    }

    /**
     * 我的会话列表（最近活跃优先）
     */
    @GetMapping
    public Result<List<Conversation>> list() {
        Long userId = UserUtils.getCurrentLoginId();
        return Result.data(conversationService.listByUser(userId));
    }

    /**
     * 会话内消息
     */
    @GetMapping("/{id}/messages")
    public Result<List<Message>> messages(@PathVariable Long id) {
        Long userId = UserUtils.getCurrentLoginId();
        return Result.data(conversationService.messages(userId, id));
    }

    /**
     * 重命名会话
     */
    @PutMapping("/{id}")
    public Result<Void> rename(@PathVariable Long id, @RequestBody Map<String, String> body) {
        Long userId = UserUtils.getCurrentLoginId();
        conversationService.rename(userId, id, body.get("title"));
        return Result.ok();
    }

    /**
     * 删除会话（不删除学习资产）
     */
    @DeleteMapping("/{id}")
    public Result<Void> delete(@PathVariable Long id) {
        Long userId = UserUtils.getCurrentLoginId();
        conversationService.delete(userId, id);
        return Result.ok("已删除会话");
    }
}
