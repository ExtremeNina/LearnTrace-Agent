package com.xueji.agent.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.xueji.agent.domain.entity.Course;
import com.xueji.agent.domain.entity.Note;
import com.xueji.agent.domain.entity.QuestionRecord;
import com.xueji.agent.domain.entity.User;
import com.xueji.agent.domain.vo.ReviewCardVO;
import com.xueji.agent.mapper.CourseMapper;
import com.xueji.agent.mapper.NoteMapper;
import com.xueji.agent.mapper.QuestionRecordMapper;
import com.xueji.agent.mapper.UserMapper;
import com.xueji.agent.service.HomeService;
import com.xueji.agent.service.LearningStatsService;
import com.xueji.agent.service.ReviewService;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 首页仪表盘聚合实现：纯确定性查询（无 LLM），复习口径复用 ReviewService，
 * 本周统计复用 LearningStatsService（与简报 / get_learning_status 同一份口径）
 */
@Service
public class HomeServiceImpl implements HomeService {

    private static final int RECENT_COURSE_LIMIT = 4;
    private static final int TODAY_QUEUE_LIMIT = 3;

    @Resource
    private UserMapper userMapper;

    @Resource
    private CourseMapper courseMapper;

    @Resource
    private NoteMapper noteMapper;

    @Resource
    private QuestionRecordMapper questionRecordMapper;

    @Resource
    private ReviewService reviewService;

    @Resource
    private LearningStatsService learningStatsService;

    @Override
    public Map<String, Object> overview(Long userId) {
        User user = userMapper.selectById(userId);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("nickname", user == null ? null : user.getNickname());

        // 继续学习：最近一门有播放记录的网课（没有则为 null，前端隐藏该卡）
        Course continueCourse = courseMapper.selectOne(new QueryWrapper<Course>()
                .eq("user_id", userId)
                .eq("deleted", 0)
                .isNotNull("last_studied_at")
                .orderByDesc("last_studied_at")
                .last("LIMIT 1"));
        result.put("continueCourse", continueCourse);

        // 最近学习：最近播放优先、退回更新时间（老数据无打点），上限 4 门
        List<Course> recentCourses = courseMapper.selectList(new QueryWrapper<Course>()
                .eq("user_id", userId)
                .eq("deleted", 0)
                .last("ORDER BY last_studied_at IS NULL, last_studied_at DESC, updated_at DESC LIMIT " + RECENT_COURSE_LIMIT));
        result.put("recentCourses", recentCourses);

        // 今日复习：队列前几张作标签展示
        List<ReviewCardVO> queue = reviewService.todayQueue(userId);
        result.put("todayQueue", queue.size() > TODAY_QUEUE_LIMIT ? queue.subList(0, TODAY_QUEUE_LIMIT) : queue);

        // 学习数据四格：复习口径来自 ReviewService.stats（dueCount / total / reviewedToday）
        Map<String, Object> reviewStats = reviewService.stats(userId);
        Map<String, Object> stats = new LinkedHashMap<>();
        stats.put("dueCount", reviewStats.get("dueCount"));
        stats.put("reviewedToday", reviewStats.get("reviewedToday"));
        stats.put("totalCards", reviewStats.get("total"));
        stats.put("coursesTotal", courseMapper.selectCount(new QueryWrapper<Course>()
                .eq("user_id", userId).eq("deleted", 0)));
        stats.put("notesTotal", noteMapper.selectCount(new QueryWrapper<Note>()
                .eq("user_id", userId).eq("deleted", 0).eq("node_type", 0)));
        stats.put("questionsTotal", questionRecordMapper.selectCount(new QueryWrapper<QuestionRecord>()
                .eq("user_id", userId).eq("deleted", 0)));
        result.put("stats", stats);

        // 本周只读统计（决策：本周学习目标第一版降级为只读）
        Map<String, Object> snapshot = learningStatsService.getStatsSnapshot(userId);
        Map<String, Object> week = new LinkedHashMap<>();
        week.put("newNotes", snapshot.get("notesCreatedThisWeek"));
        week.put("reviewed", snapshot.get("reviewedThisWeek"));
        result.put("week", week);

        return result;
    }
}
