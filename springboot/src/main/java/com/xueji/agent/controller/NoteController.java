package com.xueji.agent.controller;

import com.xueji.agent.common.Result;
import com.xueji.agent.domain.vo.NoteTreeNodeVO;
import com.xueji.agent.service.NoteService;
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
 * 笔记接口（PRD §3.4）：分层树 / 详情 / 新建 / 重命名 / 移动 / 删除 / 正文 / 知识联系
 */
@RequestMapping("/notes")
@RestController
public class NoteController {

    @Resource
    private NoteService noteService;

    /** 笔记分层树（分组 + 笔记叶子） */
    @GetMapping("/tree")
    public Result<List<NoteTreeNodeVO>> tree() {
        Long userId = UserUtils.getCurrentLoginId();
        return Result.data(noteService.tree(userId));
    }

    /** 笔记详情：正文 + 知识联系 + 关联网课标题 */
    @GetMapping("/{id}")
    public Result<Map<String, Object>> detail(@PathVariable Long id) {
        Long userId = UserUtils.getCurrentLoginId();
        return Result.data(noteService.detail(userId, id));
    }

    /** 新建分组 */
    @PostMapping("/group")
    public Result<Long> createGroup(@RequestBody Map<String, Object> body) {
        Long userId = UserUtils.getCurrentLoginId();
        Long parentId = body.get("parentId") == null ? null : Long.valueOf(String.valueOf(body.get("parentId")));
        String name = String.valueOf(body.getOrDefault("name", ""));
        return Result.data(noteService.createGroup(userId, parentId, name));
    }

    /** 新建笔记 */
    @PostMapping("/note")
    public Result<Long> createNote(@RequestBody Map<String, Object> body) {
        Long userId = UserUtils.getCurrentLoginId();
        Long parentId = body.get("parentId") == null ? null : Long.valueOf(String.valueOf(body.get("parentId")));
        String title = String.valueOf(body.getOrDefault("title", ""));
        return Result.data(noteService.createNote(userId, parentId, title));
    }

    /** 重命名（分组 / 笔记通用） */
    @PutMapping("/{id}/rename")
    public Result<Void> rename(@PathVariable Long id, @RequestBody Map<String, String> body) {
        Long userId = UserUtils.getCurrentLoginId();
        noteService.rename(userId, id, body.get("name"));
        return Result.ok();
    }

    /** 移动节点到目标分组（parentId 为空表示根目录） */
    @PutMapping("/{id}/move")
    public Result<Void> move(@PathVariable Long id, @RequestBody Map<String, Object> body) {
        Long userId = UserUtils.getCurrentLoginId();
        Long parentId = body.get("parentId") == null ? null : Long.valueOf(String.valueOf(body.get("parentId")));
        noteService.move(userId, id, parentId);
        return Result.ok();
    }

    /** 删除（笔记软删；分组仅允许删除空分组） */
    @DeleteMapping("/{id}")
    public Result<Void> delete(@PathVariable Long id) {
        Long userId = UserUtils.getCurrentLoginId();
        noteService.delete(userId, id);
        return Result.ok("已删除");
    }

    /** 更新笔记正文（在线编辑保存） */
    @PutMapping("/{id}/content")
    public Result<Void> updateContent(@PathVariable Long id, @RequestBody Map<String, String> body) {
        Long userId = UserUtils.getCurrentLoginId();
        noteService.updateContent(userId, id, body.get("content"));
        return Result.ok("已保存");
    }

    /** 添加知识联系 */
    @PostMapping("/{id}/links")
    public Result<Void> addLink(@PathVariable Long id, @RequestBody Map<String, Object> body) {
        Long userId = UserUtils.getCurrentLoginId();
        String linkType = String.valueOf(body.getOrDefault("linkType", ""));
        Long targetId = Long.valueOf(String.valueOf(body.get("targetId")));
        Integer tsSec = body.get("tsSec") == null ? null : Integer.valueOf(String.valueOf(body.get("tsSec")));
        String remark = body.get("remark") == null ? null : String.valueOf(body.get("remark"));
        noteService.addLink(userId, id, linkType, targetId, tsSec, remark);
        return Result.ok("已添加知识联系");
    }

    /** 修改知识联系的说明 */
    @PutMapping("/{id}/links/{linkId}")
    public Result<Void> updateLinkRemark(@PathVariable Long id, @PathVariable Long linkId, @RequestBody Map<String, String> body) {
        Long userId = UserUtils.getCurrentLoginId();
        noteService.updateLinkRemark(userId, id, linkId, body.get("remark"));
        return Result.ok("已更新说明");
    }

    /** 删除知识联系 */
    @DeleteMapping("/{id}/links/{linkId}")
    public Result<Void> removeLink(@PathVariable Long id, @PathVariable Long linkId) {
        Long userId = UserUtils.getCurrentLoginId();
        noteService.removeLink(userId, id, linkId);
        return Result.ok("已删除知识联系");
    }
}
