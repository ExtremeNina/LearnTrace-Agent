package com.xueji.agent.service;

import com.xueji.agent.domain.entity.StudyTimeLog;
import com.xueji.agent.mapper.StudyTimeLogMapper;
import com.xueji.agent.service.impl.StudyTimeServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 学习时长心跳测试：无行新建、有行累加、单次截断 300 秒、非法值忽略、分钟取整
 */
class StudyTimeServiceImplTest {

    private StudyTimeLogMapper studyTimeLogMapper;
    private StudyTimeServiceImpl service;

    @BeforeEach
    void setUp() {
        studyTimeLogMapper = mock(StudyTimeLogMapper.class);
        service = new StudyTimeServiceImpl();
        setField("studyTimeLogMapper", studyTimeLogMapper);
    }

    private void setField(String name, Object value) {
        try {
            java.lang.reflect.Field f = StudyTimeServiceImpl.class.getDeclaredField(name);
            f.setAccessible(true);
            f.set(service, value);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

    @Test
    void heartbeatShouldInsertWhenNoRow() {
        when(studyTimeLogMapper.selectOne(any())).thenReturn(null);

        service.heartbeat(1L, 60);

        ArgumentCaptor<StudyTimeLog> captor = ArgumentCaptor.forClass(StudyTimeLog.class);
        verify(studyTimeLogMapper).insert(captor.capture());
        assertEquals(60, captor.getValue().getDurationSec());
        assertEquals(LocalDate.now(), captor.getValue().getStudyDate());
        verify(studyTimeLogMapper, never()).updateById(any(StudyTimeLog.class));
    }

    @Test
    void heartbeatShouldAccumulateExistingRow() {
        StudyTimeLog existing = new StudyTimeLog().setId(5L).setDurationSec(120);
        when(studyTimeLogMapper.selectOne(any())).thenReturn(existing);

        service.heartbeat(1L, 60);

        assertEquals(180, existing.getDurationSec());
        verify(studyTimeLogMapper).updateById(existing);
        verify(studyTimeLogMapper, never()).insert(any(StudyTimeLog.class));
    }

    @Test
    void heartbeatShouldClampAndIgnoreInvalid() {
        when(studyTimeLogMapper.selectOne(any())).thenReturn(null);

        service.heartbeat(1L, 100000);
        ArgumentCaptor<StudyTimeLog> captor = ArgumentCaptor.forClass(StudyTimeLog.class);
        verify(studyTimeLogMapper).insert(captor.capture());
        assertEquals(300, captor.getValue().getDurationSec());

        service.heartbeat(1L, 0);
        service.heartbeat(1L, null);
        verify(studyTimeLogMapper).insert(any(StudyTimeLog.class));
    }

    @Test
    void todayMinutesShouldFloorDivide() {
        when(studyTimeLogMapper.selectOne(any())).thenReturn(null);
        assertEquals(0, service.todayMinutes(1L));

        when(studyTimeLogMapper.selectOne(any())).thenReturn(new StudyTimeLog().setDurationSec(755));
        assertEquals(12, service.todayMinutes(1L));

        when(studyTimeLogMapper.selectOne(any())).thenReturn(new StudyTimeLog().setDurationSec(null));
        assertEquals(0, service.todayMinutes(1L));
    }
}
