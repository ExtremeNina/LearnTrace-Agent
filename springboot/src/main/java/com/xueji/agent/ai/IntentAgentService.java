package com.xueji.agent.ai;

import cn.hutool.json.JSONArray;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.xueji.agent.ai.prompt.AgentPrompts;
import com.xueji.agent.domain.entity.Course;
import com.xueji.agent.domain.entity.Message;
import com.xueji.agent.domain.entity.UserProfile;
import com.xueji.agent.domain.vo.ChatEvent;
import com.xueji.agent.mapper.MessageMapper;
import com.xueji.agent.service.CourseService;
import com.xueji.agent.service.ProfileService;
import com.xueji.agent.service.TranscriptionService;
import com.xueji.agent.utils.RedisUtils;
import com.xueji.agent.ws.AgentEventPushService;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 意图 Agent（B27，独立于主对话 LLM）：接管视频回合的两步流程——
 *
 * <pre>
 * 回合1（带视频）askIntent：用户文本 + 时长 + 现有画像 → 按输入定制提问 + 意图选项卡片（[chip:] 标记，前端渲染可点击）；
 *                          并把视频参数写入 Redis pending（xj_intent_pending:{conversationId}，TTL 1h）
 * 回合2（用户回答）resolveAndExecute：解析回答 → {意图, 学段, 目标, 偏好} → 画像补全落档（后续课程流水线 graph
 *                          读到的即本次画像）→ 直接触发转写 / 建课（视频取 pending，无需重传）→ 确认话术
 * </pre>
 *
 * LLM 调用失败两级降级：提问降级为模板确认卡；解析失败按视频时长给默认意图（≤30min 转写 / 否则建课）。
 * 前端零改动：[chip:] 渲染与点击、转写进度、课程任务卡均复用现有链路。
 */
@Slf4j
@Service
public class IntentAgentService {

    private static final String PENDING_KEY_PREFIX = "xj_intent_pending:";
    private static final long PENDING_TTL_SEC = 3600;
    private static final int MAX_TRANSCRIBE_SEC = TranscriptionService.MAX_TRANSCRIBE_SEC;

    @Resource
    private ChatClient generationChatClient;

    @Resource
    private ProfileService profileService;

    @Resource
    private TranscriptionService transcriptionService;

    @Resource
    private CourseService courseService;

    @Resource
    private MessageMapper messageMapper;

    @Resource
    private AgentEventPushService pushService;

    @Resource
    private RedisUtils redisUtils;

    /** 待确认意图的视频参数 */
    public record PendingVideo(String videoTempPath, Integer durationSec) {
    }

    /** 意图解析结果（字段包级访问，同包单测可读） */
    public static class IntentResult {
        String intent;
        String gradeLevel;
        String goal;
        String note;
        String reply;
    }

    /** 是否存在待确认意图（AgentChatServiceImpl 回合分流的判定依据） */
    public boolean hasPending(Long conversationId) {
        return Boolean.TRUE.equals(redisUtils.doesItExist(PENDING_KEY_PREFIX + conversationId));
    }

    /** 回合1 产出：text = 确认话术；intentCardJson = 大卡片结构（前端消息 payload 渲染，历史可见） */
    public record AskResult(String text, String intentCardJson) {
    }

    /** 大卡片问题项 */
    public record CardQuestion(String title, java.util.List<String> options) {
    }

    /**
     * 回合1：按用户输入定制提问 + 意图确认大卡片；成功/降级都写入 pending。
     */
    public AskResult askIntent(Long userId, Long conversationId, String userText, String videoTempPath, Integer durationSec) {
        AskResult result;
        try {
            UserProfile profile = profileService.getByUser(userId);
            String text = generationChatClient.prompt()
                    .system(AgentPrompts.INTENT_ASK_PROMPT)
                    .user(buildAskPrompt(userText, durationSec, profile))
                    .call()
                    .content();
            result = parseAskCard(text);
            if (result == null) {
                throw new IllegalStateException("意图确认卡片输出异常");
            }
        } catch (Exception e) {
            log.warn("意图 Agent 提问失败，降级模板确认卡, conversationId={}", conversationId, e);
            result = fallbackAsk(durationSec);
        }
        rememberPending(conversationId, videoTempPath, durationSec);
        return result;
    }

    /**
     * 回合2：解析用户回答 → 画像落档 → 按意图执行。无 pending 时返回 null（交回主对话流程）。
     * 返回 assistant 回复文本
     */
    public String resolveAndExecute(Long userId, Long conversationId, String userText) {
        PendingVideo pending = readPending(conversationId);
        if (pending == null) {
            return null;
        }
        clearPending(conversationId);
        int durationSec = pending.durationSec() == null ? 0 : pending.durationSec();
        try {
            UserProfile profile = profileService.getByUser(userId);
            String text = generationChatClient.prompt()
                    .system(AgentPrompts.INTENT_RESOLVE_PROMPT)
                    .user(buildResolvePrompt(userText, durationSec, profile))
                    .call()
                    .content();
            IntentResult result = parseIntent(text);
            if (result == null) {
                throw new IllegalStateException("意图解析输出异常");
            }
            saveProfilePatch(userId, profile, result);
            return executeIntent(userId, conversationId, userText, result, pending, durationSec);
        } catch (Exception e) {
            log.warn("意图解析失败，按视频时长默认意图执行, conversationId={}", conversationId, e);
            IntentResult fallback = new IntentResult();
            fallback.intent = durationSec > MAX_TRANSCRIBE_SEC ? "COURSE" : "TRANSCRIBE";
            fallback.reply = durationSec > MAX_TRANSCRIBE_SEC
                    ? "收到！这就把视频按完整网课处理（画面识别 + AI 笔记 + 课后习题）。"
                    : "收到！这就为你提交转写。";
            return executeIntent(userId, conversationId, userText, fallback, pending, durationSec);
        }
    }

    // ---- 意图执行 ----

    private String executeIntent(Long userId, Long conversationId, String userText,
                                 IntentResult result, PendingVideo pending, int durationSec) {
        String intent = result.intent == null ? "" : result.intent.trim().toUpperCase();
        String tempPath = pending.videoTempPath();
        if (tempPath == null || !Files.exists(Path.of(tempPath))) {
            return "视频临时文件已被清理，麻烦重新上传一次，马上为你处理。";
        }
        // >30 分钟强制建课（轻量转写不适用）
        if ("TRANSCRIBE".equals(intent) && durationSec > MAX_TRANSCRIBE_SEC) {
            intent = "COURSE";
        }
        try {
            switch (intent) {
                case "COURSE" -> {
                    return createCourse(userId, conversationId, tempPath, userText);
                }
                case "QUESTION" -> {
                    // 提问内容同样依赖转写全文（完成后全文进对话，用户即可继续提问）
                    transcriptionService.submit(userId, conversationId, tempPath, durationSec);
                    return (StringUtils.hasText(result.reply) ? result.reply + "\n\n" : "")
                            + "已开始转写语音（预计 2~5 分钟），完成后全文会自动发到这里，到时你可以直接针对内容提问。";
                }
                default -> {
                    transcriptionService.submit(userId, conversationId, tempPath, durationSec);
                    return (StringUtils.hasText(result.reply) ? result.reply + "\n\n" : "")
                            + "已开始转写语音（预计 2~5 分钟），完成后全文会自动发到这里。";
                }
            }
        } catch (Exception e) {
            log.error("意图执行失败, userId={}, conversationId={}, intent={}", userId, conversationId, intent, e);
            return "处理失败了（" + e.getMessage() + "），请回复「重试」再试一次。";
        }
    }

    /** 建课三步（与 CreateCourseFromVideoTool 一致）：标题兜底 → uploadFromLocal → course_task 占位消息 */
    private String createCourse(Long userId, Long conversationId, String tempPath, String userText) {
        Path video = Path.of(tempPath);
        // course.title 非空约束：用户没指定标题时回退视频文件名（去 UUID 前缀）
        Course course = courseService.uploadFromLocal(userId, video, titleFromFileName(video), null);
        String title = course.getTitle();
        Message placeholder = new Message()
                .setConversationId(conversationId)
                .setRole("assistant")
                .setMsgType("course_task")
                .setCourseId(course.getId())
                .setContent("")
                .setPayload(new JSONObject()
                        .set("courseId", course.getId())
                        .set("title", title)
                        .set("status", "processing")
                        .set("stage", "PENDING")
                        .toString())
                .setCreatedAt(LocalDateTime.now());
        messageMapper.insert(placeholder);
        pushService.pushToUser(userId, ChatEvent.course(placeholder.getId(), "PENDING", null, null));
        log.info("意图 Agent 建课完成, userId={}, conversationId={}, courseId={}", userId, conversationId, course.getId());
        return "课程《" + title + "》已开始处理（预计几分钟），画面识别、AI 笔记与课后习题的进度会实时显示在对话中，"
                + "完成后会出现课程链接。";
    }

    // ---- 画像落档 ----

    /** 解析出的画像字段非空才覆盖（避免清掉用户已有画像） */
    private void saveProfilePatch(Long userId, UserProfile existing, IntentResult result) {
        try {
            UserProfile profile = existing == null ? new UserProfile() : existing;
            boolean changed = false;
            if (StringUtils.hasText(result.gradeLevel) && !result.gradeLevel.equals(profile.getGradeLevel())) {
                profile.setGradeLevel(result.gradeLevel.trim());
                changed = true;
            }
            if (StringUtils.hasText(result.goal) && !result.goal.equals(profile.getGoal())) {
                profile.setGoal(result.goal.trim());
                changed = true;
            }
            if (StringUtils.hasText(result.note) && !result.note.equals(profile.getNote())) {
                profile.setNote(result.note.trim());
                changed = true;
            }
            if (changed) {
                profileService.save(userId, profile);
                log.info("意图 Agent 画像已更新, userId={}", userId);
            }
        } catch (Exception e) {
            // 画像落档失败不阻塞意图执行
            log.warn("画像落档失败, userId={}", userId, e);
        }
    }

    // ---- prompt 组装 ----

    private String buildAskPrompt(String userText, Integer durationSec, UserProfile profile) {
        StringBuilder sb = new StringBuilder();
        sb.append("[用户消息]\n").append(userText == null || userText.isBlank() ? "（用户仅上传了视频，未输入文字）" : userText).append('\n');
        sb.append("\n[视频时长]\n").append(formatDuration(durationSec)).append('\n');
        if (profile != null) {
            String profileText = ContentReviewService.profileText(profile);
            if (!profileText.contains("未提供")) {
                sb.append("\n[已有学习者画像]\n").append(profileText).append('\n');
            }
        }
        return sb.toString();
    }

    private String buildResolvePrompt(String userText, int durationSec, UserProfile profile) {
        StringBuilder sb = new StringBuilder();
        sb.append("[用户回答]\n").append(userText == null || userText.isBlank() ? "（用户点了选项卡片，未输入文字）" : userText).append('\n');
        sb.append("\n[视频时长]\n").append(formatDuration(durationSec)).append('\n');
        if (profile != null) {
            String profileText = ContentReviewService.profileText(profile);
            if (!profileText.contains("未提供")) {
                sb.append("\n[已有学习者画像]\n").append(profileText).append('\n');
            }
        }
        return sb.toString();
    }

    // ---- pending 状态（Redis） ----

    private void rememberPending(Long conversationId, String videoTempPath, Integer durationSec) {
        try {
            redisUtils.setValueTimeout(PENDING_KEY_PREFIX + conversationId,
                    new JSONObject().set("videoTempPath", videoTempPath).set("durationSec", durationSec).toString(),
                    PENDING_TTL_SEC);
        } catch (Exception e) {
            log.warn("意图 pending 写入失败, conversationId={}", conversationId, e);
        }
    }

    private PendingVideo readPending(Long conversationId) {
        try {
            Object value = redisUtils.getValue(PENDING_KEY_PREFIX + conversationId);
            if (value == null) {
                return null;
            }
            JSONObject obj = JSONUtil.parseObj(value.toString());
            String tempPath = obj.getStr("videoTempPath", null);
            Integer durationSec = obj.getInt("durationSec", null);
            if (tempPath == null || durationSec == null) {
                return null;
            }
            return new PendingVideo(tempPath, durationSec);
        } catch (Exception e) {
            log.warn("意图 pending 读取失败, conversationId={}", conversationId, e);
            return null;
        }
    }

    private void clearPending(Long conversationId) {
        try {
            redisUtils.delKey(PENDING_KEY_PREFIX + conversationId);
        } catch (Exception ignored) {
        }
    }

    // ---- 解析与工具 ----

    /** 解析意图 Agent 的 JSON 输出（容错：剥围栏、找首尾大括号；公开静态便于单测） */
    public static IntentResult parseIntent(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        try {
            String cleaned = text.replace("```json", "").replace("```", "").trim();
            int start = cleaned.indexOf('{');
            int end = cleaned.lastIndexOf('}');
            if (start < 0 || end <= start) {
                return null;
            }
            JSONObject obj = JSONUtil.parseObj(cleaned.substring(start, end + 1));
            String intent = obj.getStr("intent", null);
            if (intent == null || intent.isBlank()) {
                return null;
            }
            IntentResult result = new IntentResult();
            result.intent = intent.trim().toUpperCase();
            result.gradeLevel = obj.getStr("gradeLevel", null);
            result.goal = obj.getStr("goal", null);
            result.note = obj.getStr("note", null);
            result.reply = obj.getStr("reply", null);
            return result;
        } catch (Exception e) {
            return null;
        }
    }

    /** 解析提问卡片 JSON（容错：剥围栏、找首尾大括号、questions 逐项校验；公开静态便于单测） */
    public static AskResult parseAskCard(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        try {
            String cleaned = text.replace("```json", "").replace("```", "").trim();
            int start = cleaned.indexOf('{');
            int end = cleaned.lastIndexOf('}');
            if (start < 0 || end <= start) {
                return null;
            }
            JSONObject obj = JSONUtil.parseObj(cleaned.substring(start, end + 1));
            String message = obj.getStr("message", null);
            if (message == null || message.isBlank()) {
                return null;
            }
            JSONArray array = obj.getJSONArray("questions");
            java.util.List<CardQuestion> questions = new java.util.ArrayList<>();
            if (array != null) {
                for (Object item : array) {
                    try {
                        JSONObject o = (JSONObject) item;
                        String title = o.getStr("title", null);
                        JSONArray opts = o.getJSONArray("options");
                        if (title == null || title.isBlank() || opts == null || opts.isEmpty()) {
                            continue;
                        }
                        questions.add(new CardQuestion(title.trim(),
                                opts.stream().map(String::valueOf).map(String::trim).toList()));
                    } catch (Exception ignored) {
                        // 单项解析失败跳过
                    }
                }
            }
            // hutool 不识别 record 访问器（非 bean 形式），questions 手动构建 JSONArray
            JSONArray questionArr = new JSONArray();
            for (CardQuestion q : questions) {
                JSONArray opts = new JSONArray();
                q.options().forEach(opts::add);
                questionArr.add(new JSONObject().set("title", q.title()).set("options", opts));
            }
            String cardJson = new JSONObject()
                    .set("message", message.trim())
                    .set("questions", questionArr)
                    .toString();
            return new AskResult(message.trim(), cardJson);
        } catch (Exception e) {
            return null;
        }
    }

    /** 降级模板确认卡（意图不明 → 处理方式问题；公开静态便于单测） */
    public static AskResult fallbackAsk(int durationSec) {
        boolean tooLong = durationSec > MAX_TRANSCRIBE_SEC;
        String message = "视频已收到（时长 " + formatDuration(durationSec) + "）。"
                + (tooLong
                ? "视频超过 30 分钟，将按完整网课处理：画面识别 + AI 笔记 + 课后习题。"
                : "请选择处理方式：≤30 分钟默认推荐「转写语音」；想要画面识别、AI 笔记和学习进度选「做成课程」。");
        JSONArray opts = new JSONArray();
        (tooLong ? List.of("做成课程") : List.of("转写语音", "做成课程", "提问内容")).forEach(opts::add);
        String cardJson = new JSONObject().set("message", message)
                .set("questions", new JSONArray().add(new JSONObject().set("title", "处理方式").set("options", opts)))
                .toString();
        return new AskResult(message, cardJson);
    }

    private static String formatDuration(Integer durationSec) {
        if (durationSec == null || durationSec < 0) {
            return "未知";
        }
        return String.format("%02d:%02d", durationSec / 60, durationSec % 60);
    }

    /** 视频文件名 → 课程标题（去上传链路临时文件前缀；与 CreateCourseFromVideoTool 同规则扩展） */
    private String titleFromFileName(Path video) {
        String name = video.getFileName().toString();
        int dot = name.lastIndexOf('.');
        String base = dot > 0 ? name.substring(0, dot) : name;
        base = base.replaceFirst("^xj-chat-video-\\d+-?", "").replaceFirst("^[0-9a-fA-F]{8,}-", "");
        return base.isBlank() ? "未命名课程" : base;
    }
}
