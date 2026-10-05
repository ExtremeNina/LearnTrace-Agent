package com.xueji.agent.controller;

import com.xueji.agent.common.Result;
import com.xueji.agent.domain.dto.CourseProgressDto;
import com.xueji.agent.domain.dto.CourseUpdateDto;
import com.xueji.agent.domain.entity.Course;
import com.xueji.agent.service.CourseService;
import com.xueji.agent.utils.UserUtils;
import jakarta.annotation.Resource;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;
import java.util.Map;

/**
 * 网课记录接口：上传 / 列表 / 详情 / 重试
 */
@RequestMapping("/courses")
@RestController
public class CourseController {

    @Resource
    private CourseService courseService;

    /**
     * 上传网课视频（异步处理，前端轮询或 WS 获取进度）
     */
    @PostMapping
    public Result<Course> upload(@RequestParam("file") MultipartFile file,
                                 @RequestParam(value = "title", required = false) String title,
                                 @RequestParam(value = "subject", required = false) String subject,
                                 @RequestParam(value = "expectations", required = false) String expectations,
                                 @RequestParam(value = "modelConfigId", required = false) Long modelConfigId) {
        Long userId = UserUtils.getCurrentLoginId();
        return Result.data(courseService.upload(userId, file, title, subject, expectations, modelConfigId));
    }

    /**
     * 我的网课列表
     */
    @GetMapping
    public Result<List<Course>> list() {
        Long userId = UserUtils.getCurrentLoginId();
        return Result.data(courseService.listByUser(userId));
    }

    /**
     * 编辑网课（标题 / 学科，仅更新提供的字段）
     */
    @PutMapping("/{id}")
    public Result<Course> update(@PathVariable Long id, @RequestBody CourseUpdateDto dto) {
        Long userId = UserUtils.getCurrentLoginId();
        return Result.data(courseService.updateByUser(userId, id, dto));
    }

    /**
     * 课程详情（含转写分段与关键帧识别结果）
     */
    @GetMapping("/{id}")
    public Result<Map<String, Object>> detail(@PathVariable Long id) {
        Long userId = UserUtils.getCurrentLoginId();
        return Result.data(courseService.detail(userId, id));
    }

    /**
     * 重试失败的处理任务
     */
    @PostMapping("/{id}/retry")
    public Result<Void> retry(@PathVariable Long id) {
        Long userId = UserUtils.getCurrentLoginId();
        courseService.retry(userId, id);
        return Result.ok("已重新提交处理");
    }

    /**
     * 上报播放进度（播放器定时调用，B25 首页「继续学习 / 最近学习」供数）
     */
    @PostMapping("/{id}/progress")
    public Result<Void> reportProgress(@PathVariable Long id, @RequestBody CourseProgressDto dto) {
        Long userId = UserUtils.getCurrentLoginId();
        courseService.reportProgress(userId, id, dto == null ? null : dto.getPositionSec());
        return Result.ok();
    }

    /**
     * 删除网课（逻辑删除，连带清理 AI 笔记 / 知识联系 / RAG 向量 / OSS 文件）
     */
    @DeleteMapping("/{id}")
    public Result<Void> delete(@PathVariable Long id) {
        Long userId = UserUtils.getCurrentLoginId();
        courseService.deleteByUser(userId, id);
        return Result.ok("已删除");
    }

    /**
     * 批量删除网课：处理中 / 不可删除的记录自动跳过
     */
    @PostMapping("/batch-delete")
    public Result<String> batchDelete(@RequestBody List<Long> ids) {
        Long userId = UserUtils.getCurrentLoginId();
        int deleted = courseService.deleteByUserBatch(userId, ids);
        if (deleted == ids.size()) {
            return Result.ok("已删除 " + deleted + " 门网课");
        }
        return Result.ok("已删除 " + deleted + " 门网课，" + (ids.size() - deleted) + " 门跳过（处理中或不可删除）");
    }
}
