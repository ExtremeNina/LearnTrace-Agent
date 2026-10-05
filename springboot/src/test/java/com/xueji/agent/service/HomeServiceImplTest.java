package com.xueji.agent.service;

import com.xueji.agent.domain.entity.Course;
import com.xueji.agent.domain.entity.User;
import com.xueji.agent.domain.vo.ReviewCardVO;
import com.xueji.agent.mapper.CourseMapper;
import com.xueji.agent.mapper.NoteMapper;
import com.xueji.agent.mapper.QuestionRecordMapper;
import com.xueji.agent.mapper.UserMapper;
import com.xueji.agent.service.impl.HomeServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 首页聚合测试：继续学习取最近播放、最近学习上限、今日队列截断、缺用户昵称兜底
 */
class HomeServiceImplTest {

    private UserMapper userMapper;
    private CourseMapper courseMapper;
    private NoteMapper noteMapper;
    private QuestionRecordMapper questionRecordMapper;
    private ReviewService reviewService;
    private LearningStatsService learningStatsService;
    private HomeServiceImpl service;

    @BeforeEach
    void setUp() {
        userMapper = mock(UserMapper.class);
        courseMapper = mock(CourseMapper.class);
        noteMapper = mock(NoteMapper.class);
        questionRecordMapper = mock(QuestionRecordMapper.class);
        reviewService = mock(ReviewService.class);
        learningStatsService = mock(LearningStatsService.class);
        service = new HomeServiceImpl();
        setField("userMapper", userMapper);
        setField("courseMapper", courseMapper);
        setField("noteMapper", noteMapper);
        setField("questionRecordMapper", questionRecordMapper);
        setField("reviewService", reviewService);
        setField("learningStatsService", learningStatsService);

        Map<String, Object> reviewStats = new HashMap<>();
        reviewStats.put("dueCount", 2);
        reviewStats.put("reviewedToday", 1);
        reviewStats.put("total", 9);
        when(reviewService.stats(1L)).thenReturn(reviewStats);
        when(reviewService.todayQueue(1L)).thenReturn(List.of(new ReviewCardVO(), new ReviewCardVO()));
        when(learningStatsService.getStatsSnapshot(1L)).thenReturn(Map.of(
                "notesCreatedThisWeek", 6,
                "reviewedThisWeek", 4));
        when(courseMapper.selectCount(any())).thenReturn(3L);
        when(noteMapper.selectCount(any())).thenReturn(6L);
        when(questionRecordMapper.selectCount(any())).thenReturn(8L);
    }

    private void setField(String name, Object value) {
        try {
            java.lang.reflect.Field f = HomeServiceImpl.class.getDeclaredField(name);
            f.setAccessible(true);
            f.set(service, value);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

    @Test
    void overviewShouldAggregateAllSections() {
        when(userMapper.selectById(1L)).thenReturn(new User().setId(1L).setNickname("Nina"));
        Course continueCourse = new Course().setId(10L).setTitle("Java 并发").setLastStudiedAt(java.time.LocalDateTime.now());
        when(courseMapper.selectOne(any())).thenReturn(continueCourse);
        when(courseMapper.selectList(any())).thenReturn(List.of(continueCourse));

        Map<String, Object> result = service.overview(1L);

        assertEquals("Nina", result.get("nickname"));
        assertEquals(continueCourse, result.get("continueCourse"));
        assertEquals(1, ((List<?>) result.get("recentCourses")).size());
        assertEquals(2, ((List<?>) result.get("todayQueue")).size());

        @SuppressWarnings("unchecked")
        Map<String, Object> stats = (Map<String, Object>) result.get("stats");
        assertEquals(2, stats.get("dueCount"));
        assertEquals(1, stats.get("reviewedToday"));
        assertEquals(9, stats.get("totalCards"));
        assertEquals(3L, stats.get("coursesTotal"));
        assertEquals(6L, stats.get("notesTotal"));
        assertEquals(8L, stats.get("questionsTotal"));

        @SuppressWarnings("unchecked")
        Map<String, Object> week = (Map<String, Object>) result.get("week");
        assertEquals(6, week.get("newNotes"));
        assertEquals(4, week.get("reviewed"));
    }

    @Test
    void overviewShouldLimitTodayQueueToThree() {
        when(userMapper.selectById(1L)).thenReturn(new User().setId(1L));
        when(courseMapper.selectOne(any())).thenReturn(null);
        when(courseMapper.selectList(any())).thenReturn(List.of());
        when(reviewService.todayQueue(1L)).thenReturn(List.of(
                new ReviewCardVO(), new ReviewCardVO(), new ReviewCardVO(), new ReviewCardVO(), new ReviewCardVO()));

        Map<String, Object> result = service.overview(1L);

        assertEquals(3, ((List<?>) result.get("todayQueue")).size());
        assertNull(result.get("continueCourse"));
    }

    @Test
    void overviewShouldTolerateMissingUser() {
        when(userMapper.selectById(1L)).thenReturn(null);
        when(courseMapper.selectOne(any())).thenReturn(null);
        when(courseMapper.selectList(any())).thenReturn(List.of());

        Map<String, Object> result = service.overview(1L);

        assertNull(result.get("nickname"));
        assertTrue(((List<?>) result.get("recentCourses")).isEmpty());
        // 聚合只读，从不写库
        verify(courseMapper, times(0)).updateById(any(Course.class));
    }
}
