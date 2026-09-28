package com.xueji.agent.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.xueji.agent.common.MqKeys;
import com.xueji.agent.domain.entity.Course;
import com.xueji.agent.domain.entity.CourseFrame;
import com.xueji.agent.domain.entity.CourseTranscriptSegment;
import com.xueji.agent.exception.BusinessException;
import com.xueji.agent.mapper.CourseFrameMapper;
import com.xueji.agent.mapper.CourseMapper;
import com.xueji.agent.mapper.CourseTranscriptSegmentMapper;
import com.xueji.agent.service.CourseService;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Slf4j
@Service
public class CourseServiceImpl implements CourseService {

    /** 视频大小上限（PRD §21：500MB） */
    private static final long MAX_VIDEO_SIZE = 500L * 1024 * 1024;

    @Resource
    private CourseMapper courseMapper;

    @Resource
    private CourseTranscriptSegmentMapper transcriptMapper;

    @Resource
    private CourseFrameMapper frameMapper;

    @Resource
    private RabbitTemplate rabbitTemplate;

    @Override
    public Course upload(Long userId, MultipartFile file, String title) {
        if (file == null || file.isEmpty()) {
            throw new BusinessException("请选择要上传的视频文件");
        }
        if (file.getSize() > MAX_VIDEO_SIZE) {
            throw new BusinessException("视频大小不能超过 500MB");
        }
        String original = file.getOriginalFilename() == null ? "未命名课程" : file.getOriginalFilename();
        String derivedTitle = title == null || title.isBlank()
                ? original.substring(0, Math.max(original.lastIndexOf('.'), 0) == 0
                        ? original.length() : original.lastIndexOf('.'))
                : title;

        // 保存到本地临时目录，OSS 上传与 FFmpeg 处理都在流水线（MQ 消费者）中异步进行
        Path temp;
        try {
            Path dir = Path.of(System.getProperty("java.io.tmpdir"), "xueji-upload");
            Files.createDirectories(dir);
            temp = dir.resolve(UUID.randomUUID() + "-" + original);
            file.transferTo(temp.toFile());
        } catch (IOException e) {
            throw new BusinessException("视频保存失败，请重试");
        }

        Course course = new Course()
                .setUserId(userId)
                .setTitle(derivedTitle)
                .setStatus("PENDING")
                .setCreatedAt(LocalDateTime.now())
                .setUpdatedAt(LocalDateTime.now());
        courseMapper.insert(course);

        // 投递处理消息（JSON 字符串负载，避免 JDK 序列化的反序列化白名单问题）
        String payload = cn.hutool.json.JSONUtil.createObj()
                .set("courseId", course.getId())
                .set("tempPath", temp.toString())
                .toString();
        rabbitTemplate.convertAndSend(MqKeys.COURSE_EXCHANGE, MqKeys.COURSE_PROCESS_ROUTING, payload);
        log.info("网课处理消息已投递, courseId={}, temp={}", course.getId(), temp);
        return course;
    }

    @Override
    public List<Course> listByUser(Long userId) {
        return courseMapper.selectList(new QueryWrapper<Course>()
                .eq("user_id", userId)
                .eq("deleted", 0)
                .orderByDesc("created_at"));
    }

    @Override
    public Map<String, Object> detail(Long userId, Long courseId) {
        Course course = checkOwnership(userId, courseId);
        List<CourseTranscriptSegment> transcript = transcriptMapper.selectList(new QueryWrapper<CourseTranscriptSegment>()
                .eq("course_id", courseId)
                .orderByAsc("sort"));
        List<CourseFrame> frames = frameMapper.selectList(new QueryWrapper<CourseFrame>()
                .eq("course_id", courseId)
                .orderByAsc("time_sec"));

        Map<String, Object> result = new HashMap<>();
        result.put("course", course);
        result.put("transcript", transcript);
        result.put("frames", frames);
        return result;
    }

    @Override
    public void retry(Long userId, Long courseId) {
        Course course = checkOwnership(userId, courseId);
        if (!"FAILED".equals(course.getStatus())) {
            throw new BusinessException("仅处理失败的网课可以重试");
        }
        course.setStatus("PENDING").setErrorMsg(null).setUpdatedAt(LocalDateTime.now());
        courseMapper.updateById(course);
        // 重试时本地临时文件可能已清理，仅重发消息由流水线校验（文件丢失会再次置为 FAILED 并提示重新上传）
        Map<String, Object> payload = new HashMap<>();
        payload.put("courseId", courseId);
        payload.put("tempPath", "");
        rabbitTemplate.convertAndSend(MqKeys.COURSE_EXCHANGE, MqKeys.COURSE_PROCESS_ROUTING, payload);
    }

    private Course checkOwnership(Long userId, Long courseId) {
        Course course = courseMapper.selectById(courseId);
        if (course == null || !course.getUserId().equals(userId) || Integer.valueOf(1).equals(course.getDeleted())) {
            throw new BusinessException(404, "网课不存在");
        }
        return course;
    }
}
