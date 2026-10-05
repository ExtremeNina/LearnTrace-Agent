package com.xueji.agent.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.xueji.agent.domain.entity.Course;
import com.xueji.agent.domain.entity.CourseFrame;
import com.xueji.agent.domain.entity.CourseTranscriptSegment;
import com.xueji.agent.domain.entity.Note;
import com.xueji.agent.domain.entity.QuestionRecord;
import com.xueji.agent.domain.entity.User;
import com.xueji.agent.domain.vo.ReviewCardVO;
import com.xueji.agent.mapper.CourseFrameMapper;
import com.xueji.agent.mapper.CourseMapper;
import com.xueji.agent.mapper.CourseTranscriptSegmentMapper;
import com.xueji.agent.mapper.NoteMapper;
import com.xueji.agent.mapper.QuestionRecordMapper;
import com.xueji.agent.mapper.UserMapper;
import com.xueji.agent.service.HomeService;
import com.xueji.agent.service.LearningStatsService;
import com.xueji.agent.service.ReviewService;
import com.xueji.agent.service.StudyTimeService;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadPoolExecutor;

/**
 * 首页仪表盘聚合实现：纯确定性查询（无 LLM），复习口径复用 ReviewService，
 * 本周统计复用 LearningStatsService（与简报 / get_learning_status 同一份口径）
 */
@Slf4j
@Service
public class HomeServiceImpl implements HomeService {

    private static final int RECENT_COURSE_LIMIT = 4;
    private static final int TODAY_QUEUE_LIMIT = 3;
    /** 继续学习卡「本课重点」条数上限 */
    private static final int KEY_POINT_LIMIT = 4;
    /** 单条重点文本截断长度 */
    private static final int KEY_POINT_MAX_LEN = 40;

    @Resource
    private UserMapper userMapper;

    @Resource
    private CourseMapper courseMapper;

    @Resource
    private NoteMapper noteMapper;

    @Resource
    private QuestionRecordMapper questionRecordMapper;

    @Resource
    private CourseFrameMapper courseFrameMapper;

    @Resource
    private CourseTranscriptSegmentMapper transcriptSegmentMapper;

    @Resource
    private ReviewService reviewService;

    @Resource
    private LearningStatsService learningStatsService;

    @Resource
    private StudyTimeService studyTimeService;

    /** LLM 提炼本课重点用（无工具无记忆；仅本页兜底场景，异步执行） */
    @Resource(name = "generationChatClient")
    private ChatClient generationChatClient;

    @Resource(name = "courseExecutor")
    private ThreadPoolExecutor courseExecutor;

    @Override
    public Map<String, Object> overview(Long userId) {
        User user = userMapper.selectById(userId);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("nickname", user == null ? null : user.getNickname());

        // 继续学习：最近一门有播放记录的网课；无打点记录（老数据 / 未播放过）时回退最近更新的一门，保证卡片常驻可见
        Course continueCourse = courseMapper.selectOne(new QueryWrapper<Course>()
                .eq("user_id", userId)
                .eq("deleted", 0)
                .isNotNull("last_studied_at")
                .orderByDesc("last_studied_at")
                .last("LIMIT 1"));
        if (continueCourse == null) {
            continueCourse = courseMapper.selectOne(new QueryWrapper<Course>()
                    .eq("user_id", userId)
                    .eq("deleted", 0)
                    .orderByDesc("updated_at")
                    .last("LIMIT 1"));
        }
        result.put("continueCourse", continueCourse);
        // 本课重点：继续学习课程最新 AI 笔记的「知识点」小节（无笔记 / 无小节则空列表，前端隐藏面板）
        result.put("keyPoints", extractKeyPoints(userId, continueCourse));

        // 最近学习：最近播放优先、退回更新时间（老数据无打点），上限 4 门
        List<Course> recentCourses = courseMapper.selectList(new QueryWrapper<Course>()
                .eq("user_id", userId)
                .eq("deleted", 0)
                .last("ORDER BY last_studied_at IS NULL, last_studied_at DESC, updated_at DESC LIMIT " + RECENT_COURSE_LIMIT));
        result.put("recentCourses", recentCourses);

        // 课程封面：各课第一张抽帧图（time_sec 最小，OSS 完整 URL）
        List<Course> coverCourses = new ArrayList<>(recentCourses);
        boolean continueInRecent = false;
        for (Course item : recentCourses) {
            if (continueCourse != null && item.getId().equals(continueCourse.getId())) {
                continueInRecent = true;
            }
        }
        if (continueCourse != null && !continueInRecent) {
            coverCourses.add(continueCourse);
        }
        Map<String, String> coverUrls = new LinkedHashMap<>();
        for (Course item : coverCourses) {
            CourseFrame frame = courseFrameMapper.selectOne(new QueryWrapper<CourseFrame>()
                    .eq("course_id", item.getId())
                    .orderByAsc("time_sec")
                    .last("LIMIT 1"));
            if (frame != null && frame.getOssKey() != null) {
                coverUrls.put(String.valueOf(item.getId()), frame.getOssKey());
            }
        }
        result.put("coverUrls", coverUrls);

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
        stats.put("todayStudyMinutes", studyTimeService.todayMinutes(userId));
        result.put("stats", stats);

        // 本周只读统计（决策：本周学习目标第一版降级为只读）
        Map<String, Object> snapshot = learningStatsService.getStatsSnapshot(userId);
        Map<String, Object> week = new LinkedHashMap<>();
        week.put("newNotes", snapshot.get("notesCreatedThisWeek"));
        week.put("reviewed", snapshot.get("reviewedThisWeek"));
        result.put("week", week);

        return result;
    }

    /** 本课重点：优先 LLM 缓存 → 课程最新 AI 笔记「知识点」小节 → 转写前几段（并异步触发 LLM 提炼缓存） */
    private List<String> extractKeyPoints(Long userId, Course course) {
        if (course == null) {
            return List.of();
        }
        // 1) 已缓存的 LLM 提炼重点
        if (course.getKeyPoints() != null && !course.getKeyPoints().isBlank()) {
            List<String> cached = parseKeyPointsJson(course.getKeyPoints());
            if (!cached.isEmpty()) {
                return cached;
            }
        }
        // 2) 课程最新 AI 笔记的「知识点」小节
        Note note = noteMapper.selectOne(new QueryWrapper<Note>()
                .eq("user_id", userId)
                .eq("course_id", course.getId())
                .eq("source_type", 1)
                .eq("deleted", 0)
                .orderByDesc("id")
                .last("LIMIT 1"));
        if (note != null) {
            List<String> points = parseKnowledgePoints(note.getContent(), KEY_POINT_LIMIT);
            if (!points.isEmpty()) {
                return points;
            }
        }
        // 3) 回退：转写分段（本次展示摘录；异步 LLM 提炼缓存，下次刷新生效）
        List<CourseTranscriptSegment> segments = transcriptSegmentMapper.selectList(new QueryWrapper<CourseTranscriptSegment>()
                .eq("course_id", course.getId())
                .orderByAsc("sort")
                .last("LIMIT 10"));
        List<String> points = new ArrayList<>();
        StringBuilder corpus = new StringBuilder();
        for (CourseTranscriptSegment segment : segments) {
            String text = segment.getText();
            if (text == null || text.isBlank()) {
                continue;
            }
            String trimmed = text.trim();
            corpus.append(trimmed).append('\n');
            if (points.size() < KEY_POINT_LIMIT) {
                points.add(trimmed.length() > KEY_POINT_MAX_LEN ? trimmed.substring(0, KEY_POINT_MAX_LEN) + "…" : trimmed);
            }
        }
        // 转写有内容但还没提炼过：异步 LLM 生成并缓存（失败静默，下次重新触发）
        if (!points.isEmpty() && course.getKeyPoints() == null && courseExecutor != null && generationChatClient != null) {
            scheduleKeyPointGeneration(course.getId(), corpus.toString());
        }
        return points;
    }

    /** 异步 LLM 提炼本课重点并缓存到 course.key_points（JSON 数组；失败静默，下次重新触发） */
    private void scheduleKeyPointGeneration(Long courseId, String transcriptText) {
        courseExecutor.execute(() -> {
            try {
                String content = generationChatClient.prompt()
                        .system("你是课程助教。从网课转写内容中提炼 3~4 个课程重点，中文，每条不超过 40 字。只输出 JSON 字符串数组，不要输出任何其他内容。")
                        .user("网课转写内容：\n" + transcriptText)
                        .call()
                        .content();
                if (content == null || content.isBlank()) {
                    return;
                }
                String json = content.trim().replaceFirst("^```(json)?", "").replaceFirst("```$", "").trim();
                List<String> points = parseKeyPointsJson(json);
                if (!points.isEmpty()) {
                    courseMapper.updateById(new Course().setId(courseId).setKeyPoints(cn.hutool.json.JSONUtil.toJsonStr(points)));
                }
            } catch (Exception e) {
                log.warn("本课重点 LLM 提炼失败, courseId={}", courseId, e);
            }
        });
    }

    /** 解析 JSON 数组字符串为要点列表（容错：非数组 / 空项跳过，上限 6 条） */
    private static List<String> parseKeyPointsJson(String json) {
        try {
            List<Object> raw = cn.hutool.json.JSONUtil.parseArray(json);
            List<String> points = new ArrayList<>();
            for (Object item : raw) {
                String s = String.valueOf(item).trim();
                if (!s.isEmpty() && points.size() < 6) {
                    points.add(s);
                }
            }
            return points;
        } catch (Exception e) {
            return List.of();
        }
    }

    /**
     * 从 AI 笔记 Markdown 抽「知识点」小节的条目（公开静态纯函数，配套单测，先例同 NoteGenerationService.buildUserContent）：
     * 取「知识点」标题行之后、下一个 # 标题之前的列表项（- / * / 数字. 开头），
     * 去掉加粗与行内代码标记并截断；无该小节时回退为全文前几条列表项
     */
    public static List<String> parseKnowledgePoints(String markdown, int limit) {
        List<String> fallback = new ArrayList<>();
        if (markdown == null || markdown.isBlank()) {
            return List.of();
        }
        boolean inSection = false;
        List<String> points = new ArrayList<>();
        for (String rawLine : markdown.split("\n")) {
            String line = rawLine.trim();
            if (line.startsWith("#")) {
                if (inSection) {
                    break;
                }
                inSection = line.contains("知识点");
                continue;
            }
            String item = stripListItem(line);
            if (item == null) {
                continue;
            }
            if (inSection) {
                points.add(item);
                if (points.size() >= limit) {
                    return points;
                }
            } else if (fallback.size() < limit) {
                fallback.add(item);
            }
        }
        return points.isEmpty() ? fallback : points;
    }

    /** 识别列表项并清洗标记（- * • 数字. 开头；去 ** 加粗与 ` 行内代码），非列表项返回 null */
    private static String stripListItem(String line) {
        String item = null;
        if (line.startsWith("- ") || line.startsWith("* ") || line.startsWith("• ")) {
            item = line.substring(2).trim();
        } else if (line.matches("\\d+[.、)）]\\s*.*")) {
            item = line.replaceFirst("^\\d+[.、)）]\\s*", "").trim();
        }
        if (item == null || item.isEmpty()) {
            return null;
        }
        item = item.replace("**", "").replace("`", "");
        if (item.length() > KEY_POINT_MAX_LEN) {
            item = item.substring(0, KEY_POINT_MAX_LEN) + "…";
        }
        return item;
    }
}
