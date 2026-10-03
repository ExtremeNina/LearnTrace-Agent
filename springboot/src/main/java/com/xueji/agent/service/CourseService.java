package com.xueji.agent.service;

import com.xueji.agent.domain.dto.CourseUpdateDto;
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
    Course upload(Long userId, MultipartFile file, String title, String subject, String expectations);

    /**
     * 用户的网课列表（按创建时间倒序）
     */
    List<Course> listByUser(Long userId);

    /**
     * 编辑网课（标题 / 学科；仅更新提供的字段），返回更新后的课程
     */
    Course updateByUser(Long userId, Long courseId, CourseUpdateDto dto);

    /**
     * 课程详情：课程信息 + 转写分段 + 关键帧识别结果
     */
    Map<String, Object> detail(Long userId, Long courseId);

    /**
     * 重试失败的处理任务
     */
    void retry(Long userId, Long courseId);

    /**
     * 删除网课（逻辑删除）：连带软删课程的 AI 笔记、清理指向该课的知识联系、
     * 移除 RAG 转写向量，并异步删除 OSS 上的视频 / 音频 / 关键帧。
     * 处理中（PROCESSING）的网课不可删除，避免与流水线并发写入
     */
    void deleteByUser(Long userId, Long courseId);

    /**
     * 处理超时自愈：把长时间停留在 PROCESSING 的网课置为 FAILED
     * （流水线进程崩溃等中断场景，MQ 消息不重投），返回处理数量
     */
    int failStaleProcessing(long timeoutMinutes);
}
