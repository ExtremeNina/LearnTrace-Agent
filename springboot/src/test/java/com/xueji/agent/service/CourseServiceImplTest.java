package com.xueji.agent.service;

import com.xueji.agent.domain.dto.CourseUpdateDto;
import com.xueji.agent.domain.entity.Course;
import com.xueji.agent.exception.BusinessException;
import com.xueji.agent.mapper.CourseMapper;
import com.xueji.agent.service.impl.CourseServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 网课记录服务基础功能测试：编辑（标题与学科 / 空学科清除 / 非本人）、失败重试的 MQ 负载契约
 */
class CourseServiceImplTest {

    private CourseMapper courseMapper;
    private RabbitTemplate rabbitTemplate;
    private CourseServiceImpl service;

    @BeforeEach
    void setUp() {
        courseMapper = mock(CourseMapper.class);
        rabbitTemplate = mock(RabbitTemplate.class);
        service = new CourseServiceImpl();
        ReflectionTestUtils.setField(service, "courseMapper", courseMapper);
        ReflectionTestUtils.setField(service, "rabbitTemplate", rabbitTemplate);
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
    void retryShouldSendJsonStringPayload() {
        // B02 回归：重试负载必须与 upload 一致为 JSON 字符串——裸 HashMap 会走 JDK 序列化，
        // 消费者（Spring AMQP 3.x 禁 JDK 反序列化）直接拒绝，重试链路不可用
        when(courseMapper.selectById(15L)).thenReturn(
                new Course().setId(15L).setUserId(1L).setDeleted(0).setStatus("FAILED"));

        service.retry(1L, 15L);

        ArgumentCaptor<Object> payloadCaptor = ArgumentCaptor.forClass(Object.class);
        verify(rabbitTemplate, times(1)).convertAndSend(any(), any(), payloadCaptor.capture());
        Object payload = payloadCaptor.getValue();
        assertEquals(String.class, payload.getClass());
        cn.hutool.json.JSONObject json = cn.hutool.json.JSONUtil.parseObj((String) payload);
        assertEquals(15L, json.getLong("courseId"));
        assertEquals("", json.getStr("tempPath"));
    }

    @Test
    void retryShouldRejectNonFailedCourse() {
        when(courseMapper.selectById(15L)).thenReturn(
                new Course().setId(15L).setUserId(1L).setDeleted(0).setStatus("SUCCESS"));

        assertThrows(BusinessException.class, () -> service.retry(1L, 15L));
        verify(rabbitTemplate, times(0)).convertAndSend(any(), any(), any(Object.class));
    }
}
