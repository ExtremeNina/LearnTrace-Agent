package com.xueji.agent.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.xueji.agent.ai.RagIngestService;
import com.xueji.agent.domain.enums.CourseStatus;
import com.xueji.agent.common.MqKeys;
import com.xueji.agent.common.OwnershipCheck;
import com.xueji.agent.domain.dto.CourseUpdateDto;
import com.xueji.agent.domain.entity.ContentDocument;
import com.xueji.agent.domain.entity.ContentKnowledgePoint;
import com.xueji.agent.domain.entity.ContentSection;
import com.xueji.agent.domain.entity.Course;
import com.xueji.agent.domain.entity.CourseFrame;
import com.xueji.agent.domain.entity.CourseTranscriptSegment;
import com.xueji.agent.domain.entity.Note;
import com.xueji.agent.domain.entity.NoteLink;
import com.xueji.agent.exception.BusinessException;
import com.xueji.agent.mapper.CourseFrameMapper;
import com.xueji.agent.mapper.CourseMapper;
import com.xueji.agent.mapper.CourseTranscriptSegmentMapper;
import com.xueji.agent.mapper.NoteLinkMapper;
import com.xueji.agent.mapper.NoteMapper;
import com.xueji.agent.mq.CourseProcessMessage;
import com.xueji.agent.service.AiModelService;
import com.xueji.agent.service.CourseService;
import com.xueji.agent.service.ReviewService;
import com.xueji.agent.utils.AliUploadUtils;
import com.xueji.agent.utils.MediaUtils;
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
import java.util.concurrent.ThreadPoolExecutor;

@Slf4j
@Service
public class CourseServiceImpl implements CourseService {

    /** 视频大小上限（1GB，与对话上传一致——长视频普遍较大） */
    private static final long MAX_VIDEO_SIZE = 1024L * 1024 * 1024;

    @Resource
    private CourseMapper courseMapper;

    @Resource
    private CourseTranscriptSegmentMapper transcriptMapper;

    @Resource
    private CourseFrameMapper frameMapper;

    @Resource
    private NoteMapper noteMapper;

    @Resource
    private NoteLinkMapper noteLinkMapper;

    @Resource
    private RagIngestService ragIngestService;

    @Resource
    private com.xueji.agent.service.ContentDocumentService contentDocumentService;

    @Resource
    private com.xueji.agent.ai.NotePipelineGraphRunner notePipelineGraphRunner;

    @Resource
    private com.xueji.agent.service.CourseQuizService courseQuizService;

    @Resource
    private ReviewService reviewService;

    @Resource
    private AiModelService aiModelService;

    @Resource
    private AliUploadUtils aliUploadUtils;

    @Resource(name = "courseExecutor")
    private ThreadPoolExecutor courseExecutor;

    @Resource
    private RabbitTemplate rabbitTemplate;

    @Override
    public Course upload(Long userId, MultipartFile file, String title, String subject, String expectations, Long modelConfigId) {
        if (file == null || file.isEmpty()) {
            throw new BusinessException("请选择要上传的视频文件");
        }
        if (file.getSize() > MAX_VIDEO_SIZE) {
            throw new BusinessException("视频大小不能超过 1GB");
        }
        // 上传时选择的笔记生成模型必须属于本人（NULL = 系统默认）
        aiModelService.validateUserConfig(userId, modelConfigId);
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

        Course course = uploadFromLocal(userId, temp, derivedTitle, expectations);
        // 页面上传路径回填学科与笔记模型（对话路径两者走默认）
        course.setSubject(subject == null || subject.isBlank() ? null : subject.trim())
                .setModelConfigId(modelConfigId);
        courseMapper.updateById(course);
        return course;
    }

    @Override
    public Course uploadFromLocal(Long userId, Path temp, String title, String expectations) {
        Course course = new Course()
                .setUserId(userId)
                .setTitle(title)
                .setExpectations(expectations == null || expectations.isBlank() ? null : expectations.trim())
                .setStatus(CourseStatus.PENDING)
                .setCreatedAt(LocalDateTime.now())
                .setUpdatedAt(LocalDateTime.now());
        courseMapper.insert(course);

        // 投递处理消息（对象负载，由 RabbitMQConfig 的 Jackson 转换器序列化）
        CourseProcessMessage message = new CourseProcessMessage();
        message.setCourseId(course.getId());
        message.setTempPath(temp.toString());
        rabbitTemplate.convertAndSend(MqKeys.COURSE_EXCHANGE, MqKeys.COURSE_PROCESS_ROUTING, message);
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
    public Course updateByUser(Long userId, Long courseId, CourseUpdateDto dto) {
        Course course = checkOwnership(userId, courseId);
        if (dto.getTitle() != null && !dto.getTitle().isBlank()) {
            course.setTitle(dto.getTitle().trim());
        }
        if (dto.getSubject() != null) {
            course.setSubject(dto.getSubject().isBlank() ? null : dto.getSubject().trim());
        }
        if (dto.getStudyNote() != null) {
            course.setStudyNote(dto.getStudyNote());
        }
        course.setUpdatedAt(LocalDateTime.now());
        courseMapper.updateById(course);
        return course;
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
        Note aiNote = noteMapper.selectOne(new QueryWrapper<Note>()
                .eq("course_id", courseId)
                .eq("source_type", 1)
                .eq("deleted", 0)
                .orderByDesc("id")
                .last("LIMIT 1"));

        Map<String, Object> result = new HashMap<>();
        result.put("course", course);
        result.put("transcript", transcript);
        result.put("frames", frames);
        result.put("note", aiNote);
        // ContentDocument 语义层（B26 阶段 2）：章节导航 + 知识点
        ContentDocument document = contentDocumentService.findByCourse(courseId);
        result.put("document", document);
        if (document != null) {
            result.put("sections", contentDocumentService.listSections(document.getId()));
            result.put("knowledgePoints", contentDocumentService.listKnowledgePoints(document.getId()));
        }
        // 课后习题（B26 习题产物化）
        result.put("quizQuestions", courseQuizService.listByCourse(courseId));
        return result;
    }

    @Override
    public void regenerateContent(Long userId, Long courseId) {
        Course course = checkOwnership(userId, courseId);
        if (!CourseStatus.SUCCESS.equals(course.getStatus())) {
            throw new BusinessException("网课处理完成后才能重新生成内容");
        }
        final Course courseRef = course;
        courseExecutor.execute(() -> {
            try {
                List<CourseTranscriptSegment> rows = transcriptMapper.selectList(
                        new QueryWrapper<CourseTranscriptSegment>().eq("course_id", courseId).orderByAsc("sort"));
                int durationSec = courseRef.getDuration() == null ? 0 : courseRef.getDuration();
                // 统一走 Graph 编排（理解 → 评审 → 渲染 → 课后习题），与流水线同链路
                notePipelineGraphRunner.run(courseRef, rows, List.of(), durationSec,
                        (stage, text) -> log.info("课程内容重新生成, courseId={}, stage={}", courseId, stage));
                log.info("课程内容已重新生成, courseId={}", courseId);
            } catch (Exception e) {
                log.warn("课程内容重生成失败, courseId={}", courseId, e);
            }
        });
    }

    @Override
    public void retry(Long userId, Long courseId) {
        Course course = checkOwnership(userId, courseId);
        if (!CourseStatus.FAILED.equals(course.getStatus())) {
            throw new BusinessException("仅处理失败的网课可以重试");
        }
        course.setStatus(CourseStatus.PENDING).setStage(null).setErrorMsg(null).setUpdatedAt(LocalDateTime.now());
        courseMapper.updateById(course);
        // 重试时本地临时文件可能已清理，仅重发消息由流水线校验（文件丢失会再次置为 FAILED 并提示重新上传）
        CourseProcessMessage message = new CourseProcessMessage();
        message.setCourseId(courseId);
        message.setTempPath("");
        rabbitTemplate.convertAndSend(MqKeys.COURSE_EXCHANGE, MqKeys.COURSE_PROCESS_ROUTING, message);
    }

    @Override
    public void reportProgress(Long userId, Long courseId, Integer positionSec) {
        Course course = checkOwnership(userId, courseId);
        // 位置非负截断；老数据 duration 可能为 NULL（流水线前），无时长只记位置不记百分比
        int position = positionSec == null || positionSec < 0 ? 0 : positionSec;
        Integer progress = null;
        if (course.getDuration() != null && course.getDuration() > 0) {
            progress = Math.min(100, position * 100 / course.getDuration());
        }
        course.setLastPositionSec(position);
        course.setProgressPct(progress);
        course.setLastStudiedAt(LocalDateTime.now());
        // 不动 updatedAt：播放进度是高频打点，updatedAt 保留给内容 / 元数据编辑语义
        courseMapper.updateById(course);
        probeMissingDuration(course, position);
    }

    /**
     * 老课程 duration 缺失时懒探测：ffprobe 直接读 OSS URL 回填时长并补算百分比（一次生效，后续上报不再探测）
     */
    private void probeMissingDuration(Course course, int position) {
        if (course.getDuration() != null || course.getVideoOssKey() == null || course.getVideoOssKey().isBlank()) {
            return;
        }
        Long courseId = course.getId();
        String videoUrl = course.getVideoOssKey();
        courseExecutor.execute(() -> {
            try {
                int duration = MediaUtils.ffprobeDurationSec(videoUrl);
                if (duration > 0) {
                    Course update = new Course().setId(courseId)
                            .setDuration(duration)
                            .setProgressPct(Math.min(100, position * 100 / duration))
                            .setUpdatedAt(LocalDateTime.now());
                    courseMapper.updateById(update);
                }
            } catch (Exception e) {
                log.warn("课程时长懒探测失败, courseId={}", courseId, e);
            }
        });
    }

    @Override
    public void deleteByUser(Long userId, Long courseId) {
        Course course = checkOwnership(userId, courseId);
        if (CourseStatus.PROCESSING.equals(course.getStatus())) {
            throw new BusinessException("网课正在处理中，暂不能删除；可等处理完成或失败后再删除");
        }
        course.setDeleted(1).setUpdatedAt(LocalDateTime.now());
        courseMapper.updateById(course);

        // 课程的 AI 笔记是派生产物，随课程一并软删并移出向量库与复习队列
        List<Note> aiNotes = noteMapper.selectList(new QueryWrapper<Note>()
                .eq("course_id", courseId)
                .eq("source_type", 1));
        for (Note note : aiNotes) {
            note.setDeleted(1).setUpdatedAt(LocalDateTime.now());
            noteMapper.updateById(note);
            ragIngestService.removeNote(note.getId());
            reviewService.removeBySource(userId, "note", note.getId());
        }
        // 其他笔记指向该课程的知识联系一并清理
        noteLinkMapper.delete(new QueryWrapper<NoteLink>()
                .eq("link_type", "course")
                .eq("target_id", courseId));
        // 转写分段向量移出 RAG
        ragIngestService.removeCourseTranscripts(courseId);
        // OSS 上的视频 / 音频 / 关键帧按前缀异步删除（网络 IO，不阻塞请求）
        courseExecutor.execute(() -> aliUploadUtils.deleteByPrefix("course/" + courseId + "/"));
        log.info("网课已删除, courseId={}, aiNotes={}, userId={}", courseId, aiNotes.size(), userId);
    }

    @Override
    public int deleteByUserBatch(Long userId, List<Long> courseIds) {
        if (courseIds == null || courseIds.isEmpty()) {
            return 0;
        }
        int deleted = 0;
        for (Long courseId : courseIds) {
            try {
                deleteByUser(userId, courseId);
                deleted++;
            } catch (BusinessException e) {
                // 处理中 / 不存在 / 非本人：跳过该条，不影响其余删除
                log.info("批量删除跳过 courseId={},原因={}", courseId, e.getMessage());
            }
        }
        return deleted;
    }

    @Override
    public int failStaleProcessing(long timeoutMinutes) {
        List<Course> stale = courseMapper.selectList(new QueryWrapper<Course>()
                .eq("status", CourseStatus.PROCESSING)
                .eq("deleted", 0)
                .lt("updated_at", LocalDateTime.now().minusMinutes(timeoutMinutes)));
        for (Course course : stale) {
            course.setStatus(CourseStatus.FAILED)
                    .setErrorMsg("处理超时（任务中断），请重试")
                    .setUpdatedAt(LocalDateTime.now());
            courseMapper.updateById(course);
        }
        if (!stale.isEmpty()) {
            StringBuilder ids = new StringBuilder();
            for (Course course : stale) {
                if (ids.length() > 0) {
                    ids.append(',');
                }
                ids.append(course.getId());
            }
            log.warn("网课处理超时自愈, courseIds={}, 置为 FAILED", ids);
        }
        return stale.size();
    }

    private Course checkOwnership(Long userId, Long courseId) {
        return OwnershipCheck.requireOwned(courseMapper.selectById(courseId), userId, "网课不存在");
    }
}
