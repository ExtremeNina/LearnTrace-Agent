package com.xueji.agent.controller;

import com.xueji.agent.common.Result;
import com.xueji.agent.domain.entity.Course;
import com.xueji.agent.service.CourseService;
import com.xueji.agent.utils.UserUtils;
import jakarta.annotation.Resource;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
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
                                 @RequestParam(value = "expectations", required = false) String expectations) {
        Long userId = UserUtils.getCurrentLoginId();
        return Result.data(courseService.upload(userId, file, title, expectations));
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
}
