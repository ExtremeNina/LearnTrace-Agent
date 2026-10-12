package com.xueji.agent.task;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.xueji.agent.ai.SubjectInferenceService;
import com.xueji.agent.domain.entity.Course;
import com.xueji.agent.domain.entity.CourseTranscriptSegment;
import com.xueji.agent.mapper.CourseMapper;
import com.xueji.agent.mapper.CourseTranscriptSegmentMapper;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 网课学科兜底回填定时任务（2026-10-12）：对话建课链路不采集学科（上传表单已移除），
 * 意图 Agent 也可能无线索——每日扫描 subject 为空且未删除的课程，基于标题 + 转写摘录
 * 轻量 LLM 推断回填（存量课程随之修复）。推断结果必写入（判不出写「其他」），天然防反复跑；
 * 推断失败（模型异常）的课程保持 NULL 由下一轮重试。
 */
@Slf4j
@Component
public class SubjectBackfillScheduler {

    /** 单轮处理上限：轻量限速，避免连续调用模型服务 */
    private static final int BATCH_LIMIT = 20;

    /** 转写摘录长度（字符）：覆盖足够判断学科的内容即可 */
    private static final int EXCERPT_MAX_CHARS = 500;

    @Resource
    private CourseMapper courseMapper;

    @Resource
    private CourseTranscriptSegmentMapper transcriptMapper;

    @Resource
    private SubjectInferenceService subjectInferenceService;

    /** 启动 60 秒后首跑（存量课程即时回填），此后每天一轮 */
    @Scheduled(initialDelay = 60_000, fixedDelay = 24 * 3600 * 1000L)
    public void backfill() {
        try {
            List<Course> pending = courseMapper.selectList(new QueryWrapper<Course>()
                    .eq("deleted", 0)
                    .and(q -> q.isNull("subject").or().eq("subject", ""))
                    .orderByAsc("id")
                    .last("LIMIT " + BATCH_LIMIT));
            if (pending.isEmpty()) {
                return;
            }
            int done = 0;
            for (Course course : pending) {
                try {
                    String subject = subjectInferenceService.infer(
                            course.getUserId(), course.getTitle(), transcriptExcerpt(course.getId()));
                    course.setSubject(subject).setUpdatedAt(LocalDateTime.now());
                    courseMapper.updateById(course);
                    done++;
                    Thread.sleep(500);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                } catch (Exception e) {
                    log.warn("学科回填失败（该课程保持 NULL，下一轮重试）, courseId={}", course.getId(), e);
                }
            }
            log.info("学科兜底回填完成, 扫描 {} 门, 回填 {} 门", pending.size(), done);
        } catch (Exception e) {
            log.error("学科兜底回填任务失败（下一轮重试）", e);
        }
    }

    /** 转写摘录：前 6 个分段拼接（textCorrected 优先），截断到上限 */
    private String transcriptExcerpt(Long courseId) {
        List<CourseTranscriptSegment> segments = transcriptMapper.selectList(
                new QueryWrapper<CourseTranscriptSegment>()
                        .eq("course_id", courseId)
                        .orderByAsc("sort")
                        .last("LIMIT 6"));
        StringBuilder sb = new StringBuilder();
        for (CourseTranscriptSegment segment : segments) {
            String text = segment.getTextCorrected() != null ? segment.getTextCorrected() : segment.getText();
            if (text != null) {
                sb.append(text);
            }
            if (sb.length() >= EXCERPT_MAX_CHARS) {
                break;
            }
        }
        return sb.length() > EXCERPT_MAX_CHARS ? sb.substring(0, EXCERPT_MAX_CHARS) : sb.toString();
    }
}
