package com.xueji.agent.service;

import com.xueji.agent.ai.RagIngestService;
import com.xueji.agent.domain.dto.CourseUpdateDto;
import com.xueji.agent.domain.entity.Course;
import com.xueji.agent.domain.entity.Note;
import com.xueji.agent.domain.enums.CourseStatus;
import com.xueji.agent.exception.BusinessException;
import com.xueji.agent.mapper.CourseMapper;
import com.xueji.agent.mapper.NoteLinkMapper;
import com.xueji.agent.mapper.NoteMapper;
import com.xueji.agent.mq.CourseProcessMessage;
import com.xueji.agent.service.impl.CourseServiceImpl;
import com.xueji.agent.utils.AliUploadUtils;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.concurrent.ThreadPoolExecutor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 网课记录服务基础功能测试：编辑（标题与学科 / 空学科清除 / 非本人）、失败重试的 MQ 负载契约、
 * 删除（连带 AI 笔记 / 知识联系 / 向量 / OSS）、PROCESSING 超时自愈
 */
class CourseServiceImplTest {

    private CourseMapper courseMapper;
    private NoteMapper noteMapper;
    private NoteLinkMapper noteLinkMapper;
    private RagIngestService ragIngestService;
    private ReviewService reviewService;
    private AliUploadUtils aliUploadUtils;
    private RabbitTemplate rabbitTemplate;
    private CourseServiceImpl service;

    @BeforeEach
    void setUp() {
        courseMapper = mock(CourseMapper.class);
        noteMapper = mock(NoteMapper.class);
        noteLinkMapper = mock(NoteLinkMapper.class);
        ragIngestService = mock(RagIngestService.class);
        reviewService = mock(ReviewService.class);
        aliUploadUtils = mock(AliUploadUtils.class);
        rabbitTemplate = mock(RabbitTemplate.class);
        ThreadPoolExecutor courseExecutor = mock(ThreadPoolExecutor.class);
        // 线程池 mock 直接在当前线程执行，便于验证 OSS 清理调用
        doAnswer(inv -> {
            ((Runnable) inv.getArgument(0)).run();
            return null;
        }).when(courseExecutor).execute(any(Runnable.class));
        service = new CourseServiceImpl();
        ReflectionTestUtils.setField(service, "courseMapper", courseMapper);
        ReflectionTestUtils.setField(service, "noteMapper", noteMapper);
        ReflectionTestUtils.setField(service, "noteLinkMapper", noteLinkMapper);
        ReflectionTestUtils.setField(service, "ragIngestService", ragIngestService);
        ReflectionTestUtils.setField(service, "aliUploadUtils", aliUploadUtils);
        ReflectionTestUtils.setField(service, "courseExecutor", courseExecutor);
        ReflectionTestUtils.setField(service, "rabbitTemplate", rabbitTemplate);
        ReflectionTestUtils.setField(service, "reviewService", reviewService);
    }

    @Test
    void updateByUser_titleAndSubject() {
        Course existing = new Course().setId(15L).setUserId(1L).setDeleted(0)
                .setTitle("旧标题").setSubject("数学");
        when(courseMapper.selectById(15L)).thenReturn(existing);
        when(courseMapper.updateById(any(Course.class))).thenReturn(1);

        CourseUpdateDto dto = new CourseUpdateDto();
        dto.setTitle("  计算机科学 第 1 讲  ");
        dto.setSubject("计算机");
        Course updated = service.updateByUser(1L, 15L, dto);

        assertEquals("计算机科学 第 1 讲", updated.getTitle());
        assertEquals("计算机", updated.getSubject());
        verify(courseMapper, times(1)).updateById(updated);
    }

    @Test
    void updateByUser_blankSubjectClears() {
        Course existing = new Course().setId(15L).setUserId(1L).setDeleted(0)
                .setTitle("标题").setSubject("计算机");
        when(courseMapper.selectById(15L)).thenReturn(existing);
        when(courseMapper.updateById(any(Course.class))).thenReturn(1);

        CourseUpdateDto dto = new CourseUpdateDto();
        dto.setSubject("");
        Course updated = service.updateByUser(1L, 15L, dto);

        assertEquals("标题", updated.getTitle());
        assertNull(updated.getSubject());
    }

    @Test
    void updateByUser_notOwner() {
        when(courseMapper.selectById(15L))
                .thenReturn(new Course().setId(15L).setUserId(2L).setDeleted(0));

        assertThrows(BusinessException.class,
                () -> service.updateByUser(1L, 15L, new CourseUpdateDto()));
    }

    @Test
    void retryShouldSendMessageObjectPayload() {
        // 负载契约回归：RabbitMQConfig 装配 Jackson2JsonMessageConverter 后负载直接发对象
        // （旧 B02 时代的 JSON 字符串规避已下线——字符串负载会被 Jackson 二次编码成带引号字面量）
        when(courseMapper.selectById(15L)).thenReturn(
                new Course().setId(15L).setUserId(1L).setDeleted(0).setStatus("FAILED"));

        service.retry(1L, 15L);

        ArgumentCaptor<Object> payloadCaptor = ArgumentCaptor.forClass(Object.class);
        verify(rabbitTemplate, times(1)).convertAndSend(any(), any(), payloadCaptor.capture());
        Object payload = payloadCaptor.getValue();
        assertEquals(CourseProcessMessage.class, payload.getClass());
        CourseProcessMessage message = (CourseProcessMessage) payload;
        assertEquals(15L, message.getCourseId());
        assertEquals("", message.getTempPath());
    }

    @Test
    void retryShouldRejectNonFailedCourse() {
        when(courseMapper.selectById(15L)).thenReturn(
                new Course().setId(15L).setUserId(1L).setDeleted(0).setStatus("SUCCESS"));

        assertThrows(BusinessException.class, () -> service.retry(1L, 15L));
        verify(rabbitTemplate, times(0)).convertAndSend(any(), any(), any(Object.class));
    }

    // ---- 播放进度上报 ----

    @Test
    void reportProgressShouldRecordPositionAndPercent() {
        when(courseMapper.selectById(15L)).thenReturn(
                new Course().setId(15L).setUserId(1L).setDeleted(0).setStatus(CourseStatus.SUCCESS).setDuration(600));

        service.reportProgress(1L, 15L, 300);

        ArgumentCaptor<Course> captor = ArgumentCaptor.forClass(Course.class);
        verify(courseMapper).updateById(captor.capture());
        Course saved = captor.getValue();
        assertEquals(300, saved.getLastPositionSec());
        assertEquals(50, saved.getProgressPct());
        assertNotNull(saved.getLastStudiedAt());
        assertNull(saved.getUpdatedAt());
    }

    @Test
    void reportProgressShouldClampAndSkipPercentWithoutDuration() {
        when(courseMapper.selectById(15L)).thenReturn(
                new Course().setId(15L).setUserId(1L).setDeleted(0).setStatus(CourseStatus.SUCCESS));

        service.reportProgress(1L, 15L, -30);

        ArgumentCaptor<Course> captor = ArgumentCaptor.forClass(Course.class);
        verify(courseMapper).updateById(captor.capture());
        Course saved = captor.getValue();
        assertEquals(0, saved.getLastPositionSec());
        assertNull(saved.getProgressPct());
        assertNotNull(saved.getLastStudiedAt());
    }

    @Test
    void reportProgressShouldRejectNonOwner() {
        when(courseMapper.selectById(15L)).thenReturn(
                new Course().setId(15L).setUserId(2L).setDeleted(0));

        assertThrows(BusinessException.class, () -> service.reportProgress(1L, 15L, 100));
        verify(courseMapper, times(0)).updateById(any());
    }

    // ---- 删除 ----

    @Test
    void deleteByUser_shouldCascadeAiNotesLinksVectorsAndOss() {
        Course course = new Course().setId(15L).setUserId(1L).setDeleted(0).setStatus(CourseStatus.SUCCESS);
        when(courseMapper.selectById(15L)).thenReturn(course);
        Note aiNote = new Note().setId(30L).setTitle("网课 AI 笔记").setCourseId(15L).setSourceType(1).setDeleted(0);
        when(noteMapper.selectList(any())).thenReturn(List.of(aiNote));
        when(courseMapper.updateById(any(Course.class))).thenReturn(1);
        when(noteMapper.updateById(any(Note.class))).thenReturn(1);

        service.deleteByUser(1L, 15L);

        assertEquals(1, course.getDeleted());
        assertEquals(1, aiNote.getDeleted());
        verify(ragIngestService).removeNote(30L);
        verify(ragIngestService).removeCourseTranscripts(15L);
        verify(noteLinkMapper).delete(any());
        verify(aliUploadUtils).deleteByPrefix("course/15/");
    }

    @Test
    void deleteByUser_rejectsProcessing() {
        when(courseMapper.selectById(15L)).thenReturn(
                new Course().setId(15L).setUserId(1L).setDeleted(0).setStatus(CourseStatus.PROCESSING));

        assertThrows(BusinessException.class, () -> service.deleteByUser(1L, 15L));
        verify(courseMapper, times(0)).updateById(any(Course.class));
        verify(aliUploadUtils, times(0)).deleteByPrefix(any());
    }

    @Test
    void deleteByUserBatch_shouldDeleteDeletableAndSkipRest() {
        Course deletable = new Course().setId(15L).setUserId(1L).setDeleted(0).setStatus(CourseStatus.SUCCESS);
        Course processing = new Course().setId(16L).setUserId(1L).setDeleted(0).setStatus(CourseStatus.PROCESSING);
        Course foreign = new Course().setId(17L).setUserId(2L).setDeleted(0).setStatus(CourseStatus.SUCCESS);
        when(courseMapper.selectById(15L)).thenReturn(deletable);
        when(courseMapper.selectById(16L)).thenReturn(processing);
        when(courseMapper.selectById(17L)).thenReturn(foreign);
        when(courseMapper.updateById(any(Course.class))).thenReturn(1);
        when(noteMapper.selectList(any())).thenReturn(List.of());

        int deleted = service.deleteByUserBatch(1L, List.of(15L, 16L, 17L));

        // 处理中与非本人的跳过（deleted 保持 0），可删的正常删除
        assertEquals(1, deleted);
        assertEquals(1, deletable.getDeleted());
        assertEquals(0, processing.getDeleted());
        assertEquals(0, foreign.getDeleted());
    }

    // ---- 处理超时自愈 ----

    @Test
    void failStaleProcessing_shouldMarkTimedOutCoursesFailed() {
        Course stale1 = new Course().setId(1L).setStatus(CourseStatus.PROCESSING);
        Course stale2 = new Course().setId(2L).setStatus(CourseStatus.PROCESSING);
        when(courseMapper.selectList(any())).thenReturn(List.of(stale1, stale2));
        when(courseMapper.updateById(any(Course.class))).thenReturn(1);

        int fixed = service.failStaleProcessing(60);

        assertEquals(2, fixed);
        assertEquals(CourseStatus.FAILED, stale1.getStatus());
        assertEquals(CourseStatus.FAILED, stale2.getStatus());
        verify(courseMapper, times(2)).updateById(any(Course.class));
    }
}
