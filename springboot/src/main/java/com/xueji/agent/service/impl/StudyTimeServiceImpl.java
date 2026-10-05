package com.xueji.agent.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.xueji.agent.domain.entity.StudyTimeLog;
import com.xueji.agent.mapper.StudyTimeLogMapper;
import com.xueji.agent.service.StudyTimeService;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 学习时长实现：study_time_log 一天一行，心跳直接在行上累加（个人工具，无并发刷量场景）
 */
@Service
public class StudyTimeServiceImpl implements StudyTimeService {

    /** 单次心跳秒数上限（前端 60s 一跳，放宽到 300s 容忍补报） */
    private static final int MAX_HEARTBEAT_SEC = 300;

    @Resource
    private StudyTimeLogMapper studyTimeLogMapper;

    @Override
    public void heartbeat(Long userId, Integer seconds) {
        if (seconds == null || seconds <= 0) {
            return;
        }
        int inc = Math.min(MAX_HEARTBEAT_SEC, seconds);
        LocalDate today = LocalDate.now();
        StudyTimeLog log = studyTimeLogMapper.selectOne(new QueryWrapper<StudyTimeLog>()
                .eq("user_id", userId)
                .eq("study_date", today));
        if (log == null) {
            log = new StudyTimeLog()
                    .setUserId(userId)
                    .setStudyDate(today)
                    .setDurationSec(inc)
                    .setCreatedAt(LocalDateTime.now())
                    .setUpdatedAt(LocalDateTime.now());
            studyTimeLogMapper.insert(log);
            return;
        }
        log.setDurationSec(log.getDurationSec() + inc);
        log.setUpdatedAt(LocalDateTime.now());
        studyTimeLogMapper.updateById(log);
    }

    @Override
    public int todayMinutes(Long userId) {
        StudyTimeLog log = studyTimeLogMapper.selectOne(new QueryWrapper<StudyTimeLog>()
                .eq("user_id", userId)
                .eq("study_date", LocalDate.now()));
        if (log == null || log.getDurationSec() == null) {
            return 0;
        }
        return log.getDurationSec() / 60;
    }
}
