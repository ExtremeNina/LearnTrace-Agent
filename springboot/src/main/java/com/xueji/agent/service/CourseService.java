package com.xueji.agent.service;

import com.xueji.agent.domain.entity.Course;
import com.xueji.agent.domain.entity.CourseFrame;
import com.xueji.agent.domain.entity.CourseTranscriptSegment;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;
import java.util.Map;

/**
 * 网课记录服务：上传 / 查询 / 重试
 */
public interface CourseService {

    /**
     * 上传网课视频：保存到本地临时目录、创建课程记录（PENDING）、投递处理消息。
     * 返回创建的课程（视频 URL 在流水线阶段补全）
     */
    Course upload(Long userId, MultipartFile file, String title);

    /**
     * 用户的网课列表（按创建时间倒序）
     */
    List<Course> listByUser(Long userId);

    /**
     * 课程详情：课程信息 + 转写分段 + 关键帧识别结果
     */
    Map<String, Object> detail(Long userId, Long courseId);

    /**
     * 重试失败的处理任务
     */
    void retry(Long userId, Long courseId);
}
