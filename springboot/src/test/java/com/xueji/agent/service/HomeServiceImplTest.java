package com.xueji.agent.service;

import com.xueji.agent.domain.entity.Course;
import com.xueji.agent.domain.entity.CourseFrame;
import com.xueji.agent.domain.entity.CourseTranscriptSegment;
import com.xueji.agent.domain.entity.Note;
import com.xueji.agent.domain.entity.User;
import com.xueji.agent.domain.vo.ReviewCardVO;
import com.xueji.agent.mapper.CourseFrameMapper;
import com.xueji.agent.mapper.CourseMapper;
import com.xueji.agent.mapper.CourseTranscriptSegmentMapper;
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
    private CourseFrameMapper courseFrameMapper;
    private CourseTranscriptSegmentMapper transcriptSegmentMapper;
    private ReviewService reviewService;
    private LearningStatsService learningStatsService;
    private StudyTimeService studyTimeService;
    private HomeServiceImpl service;

    @BeforeEach
    void setUp() {
        userMapper = mock(UserMapper.class);
        courseMapper = mock(CourseMapper.class);
        noteMapper = mock(NoteMapper.class);
        questionRecordMapper = mock(QuestionRecordMapper.class);
        courseFrameMapper = mock(CourseFrameMapper.class);
        transcriptSegmentMapper = mock(CourseTranscriptSegmentMapper.class);
        reviewService = mock(ReviewService.class);
        learningStatsService = mock(LearningStatsService.class);
        studyTimeService = mock(StudyTimeService.class);
        service = new HomeServiceImpl();
        setField("userMapper", userMapper);
        setField("courseMapper", courseMapper);
        setField("noteMapper", noteMapper);
        setField("questionRecordMapper", questionRecordMapper);
        setField("courseFrameMapper", courseFrameMapper);
        setField("transcriptSegmentMapper", transcriptSegmentMapper);
        setField("reviewService", reviewService);
        setField("learningStatsService", learningStatsService);
        setField("studyTimeService", studyTimeService);

        Map<String, Object> reviewStats = new HashMap<>();
        reviewStats.put("dueCount", 2);
        reviewStats.put("reviewedToday", 1);
        reviewStats.put("total", 9);
        when(reviewService.stats(1L)).thenReturn(reviewStats);
        when(reviewService.todayQueue(1L)).thenReturn(List.of(new ReviewCardVO(), new ReviewCardVO()));
        when(learningStatsService.getStatsSnapshot(1L)).thenReturn(Map.of(
                "notesCreatedThisWeek", 6,
                "reviewedThisWeek", 4));
        when(studyTimeService.todayMinutes(1L)).thenReturn(12);
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
        assertEquals(12, stats.get("todayStudyMinutes"));

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

    @Test
    void overviewShouldExtractKeyPointsFromAiNote() {
        when(userMapper.selectById(1L)).thenReturn(new User().setId(1L));
        Course course = new Course().setId(10L).setTitle("Java 并发");
        when(courseMapper.selectOne(any())).thenReturn(course);
        when(courseMapper.selectList(any())).thenReturn(List.of(course));
        Note aiNote = new Note().setContent("## 课程概览\n- 概览条目\n\n## 知识点\n- **volatile** 关键字\n- CAS 与 AQS\n\n## 总结\n- 总结条目");
        when(noteMapper.selectOne(any())).thenReturn(aiNote);

        Map<String, Object> result = service.overview(1L);

        assertEquals(List.of("volatile 关键字", "CAS 与 AQS"), result.get("keyPoints"));
    }

    @Test
    void overviewShouldPreferCachedKeyPoints() {
        when(userMapper.selectById(1L)).thenReturn(new User().setId(1L));
        Course course = new Course().setId(10L).setTitle("Java 并发").setKeyPoints("[\"要点一\",\"要点二\"]");
        when(courseMapper.selectOne(any())).thenReturn(course);
        when(courseMapper.selectList(any())).thenReturn(List.of(course));

        Map<String, Object> result = service.overview(1L);

        assertEquals(List.of("要点一", "要点二"), result.get("keyPoints"));
        // 缓存命中不查 AI 笔记 / 转写
        verify(noteMapper, times(0)).selectOne(any());
    }

    @Test
    void overviewShouldFallbackKeyPointsToTranscriptsAndMapCovers() {
        when(userMapper.selectById(1L)).thenReturn(new User().setId(1L));
        Course course = new Course().setId(10L).setTitle("Java 并发").setLastStudiedAt(java.time.LocalDateTime.now());
        when(courseMapper.selectOne(any())).thenReturn(course);
        when(courseMapper.selectList(any())).thenReturn(List.of(course));
        // 无 AI 笔记：本课重点回退转写前几段；封面取第一帧
        when(noteMapper.selectOne(any())).thenReturn(null);
        when(transcriptSegmentMapper.selectList(any())).thenReturn(List.of(
                new CourseTranscriptSegment().setCourseId(10L).setSort(1).setText("第一段转写内容"),
                new CourseTranscriptSegment().setCourseId(10L).setSort(2).setText("第二段")));
        when(courseFrameMapper.selectOne(any())).thenReturn(
                new CourseFrame().setCourseId(10L).setOssKey("https://oss/course/10/frames/0.jpg"));

        Map<String, Object> result = service.overview(1L);

        assertEquals(List.of("第一段转写内容", "第二段"), result.get("keyPoints"));
        assertEquals("https://oss/course/10/frames/0.jpg", ((Map<?, ?>) result.get("coverUrls")).get("10"));
    }

    @Test
    void parseKnowledgePointsShouldFallbackAndStrip() {
        assertTrue(HomeServiceImpl.parseKnowledgePoints(null, 4).isEmpty());
        // 无「知识点」小节：回退为全文列表项，数字 / 星号 / 顿号列表皆识别
        assertEquals(List.of("甲", "乙"), HomeServiceImpl.parseKnowledgePoints("## 概览\n- 甲\n1. 乙", 4));
        assertEquals(List.of("丙"), HomeServiceImpl.parseKnowledgePoints("## 知识点\n- **丙**\n", 4));
    }
}
