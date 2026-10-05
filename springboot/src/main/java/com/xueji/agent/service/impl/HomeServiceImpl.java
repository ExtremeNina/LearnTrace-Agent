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
import com.xueji.agent.service.StudyTimeService;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
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
    private ReviewService reviewService;

    @Resource
    private LearningStatsService learningStatsService;

    @Resource
    private StudyTimeService studyTimeService;

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
        // 本课重点：继续学习课程最新 AI 笔记的「知识点」小节（无笔记 / 无小节则空列表，前端隐藏面板）
        result.put("keyPoints", extractKeyPoints(userId, continueCourse));

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

    /** 本课重点：继续学习课程最新 AI 笔记的「知识点」小节前几条（AI 笔记 Markdown 固定结构） */
    private List<String> extractKeyPoints(Long userId, Course course) {
        if (course == null) {
            return List.of();
        }
        Note note = noteMapper.selectOne(new QueryWrapper<Note>()
                .eq("user_id", userId)
                .eq("course_id", course.getId())
                .eq("source_type", 1)
                .eq("deleted", 0)
                .orderByDesc("id")
                .last("LIMIT 1"));
        if (note == null) {
            return List.of();
        }
        return parseKnowledgePoints(note.getContent(), KEY_POINT_LIMIT);
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
