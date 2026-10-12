package com.xueji.agent.ai;

import cn.hutool.json.JSONArray;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.xueji.agent.ai.prompt.AgentPrompts;
import com.xueji.agent.common.ModelServiceErrors;
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
 * 意图 Agent（B27 立项 / 2026-10-11 固定卡改造）：接管视频回合——
 *
 * <pre>
 * 回合1（带视频）askIntent：LLM 只判意图不出卡（INTENT_JUDGE_PROMPT，理解「转写成课程」类混合表述）——
 *   TRANSCRIBE → 直接提交轻量转写（不出卡、不写 pending）；
 *   COURSE → 静态三问卡（学习目的 / 知识水平 / 讲解形式，前端每题可自定义回答），pending.stage=PROFILE；
 *   UNCLEAR → 静态单题处理方式卡（轻量转写 / 做成课程），pending.stage=ROUTE。
 *   视频参数写 Redis pending（xj_intent_pending:{conversationId}，TTL 1h）
 * 回合2（用户回答）resolveAndExecute 按 stage 分派：
 *   ROUTE：确定性分派——「做成课程」→ 出三问卡（stage=PROFILE）；「转写」→ 直接转写；分派不了按时长默认
 *   PROFILE：LLM 解析三问回答 → 画像落档（goal/level，学段从自由文本抽取）→ 建课（风格/目的/水平随课程落库）
 * </pre>
 *
 * 降级：判定 LLM 失败按时长默认（>30min 三问卡建课路径 / 否则处理方式卡）；
 * 三问解析失败默认跟学型照常建课；LLM 调用遇余额不足统一返回充值文案。
 */
@Slf4j
@Service
public class IntentAgentService {

    private static final String PENDING_KEY_PREFIX = "xj_intent_pending:";
    private static final long PENDING_TTL_SEC = 3600;
    private static final int MAX_TRANSCRIBE_SEC = TranscriptionService.MAX_TRANSCRIBE_SEC;

    /** 笔记风格常量（course.note_style 落库值，与 NoteGenerationService 渲染变体对应；唯一定义在 AgentPrompts） */
    public static final String STYLE_FOLLOW = AgentPrompts.NOTE_STYLE_FOLLOW;
    public static final String STYLE_EXAM_SPRINT = AgentPrompts.NOTE_STYLE_EXAM_SPRINT;

    private static final String STAGE_ROUTE = "ROUTE";
    private static final String STAGE_PROFILE = "PROFILE";

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

    /** 待确认意图的视频参数（stage：ROUTE=处理方式卡已出 / PROFILE=三问卡已出） */
    public record PendingVideo(String videoTempPath, Integer durationSec, String stage) {
    }

    /** 三问解析结果（公开静态便于单测） */
    public record ProfileAnswer(String goal, String level, String style, String gradeLevel, String reply) {
    }

    /** 大卡片问题项（title 与 options 为前后端约定的固定文案） */
    public record CardQuestion(String title, List<String> options) {
    }

    /** 三问卡题目与选项（讲解形式对应笔记风格渲染变体） */
    public static final List<CardQuestion> PROFILE_QUESTIONS = List.of(
            new CardQuestion("学习目的", List.of("期末备考", "系统学习", "查漏补缺", "兴趣了解")),
            new CardQuestion("知识水平", List.of("零基础入门", "有一定基础", "进阶提升")),
            new CardQuestion("讲解形式", List.of("跟学型", "考点速查型")));

    /** 是否存在待确认意图（AgentChatServiceImpl 回合分流的判定依据） */
    public boolean hasPending(Long conversationId) {
        return Boolean.TRUE.equals(redisUtils.doesItExist(PENDING_KEY_PREFIX + conversationId));
    }

    /** 回合产出：text = 确认话术；intentCardJson = 大卡片结构（前端消息 payload 渲染，历史可见） */
    public record AskResult(String text, String intentCardJson) {
    }

    /**
     * 回合1：LLM 判定意图 → 直接转写 / 三问卡 / 处理方式卡。
     */
    public AskResult askIntent(Long userId, Long conversationId, String userText, String videoTempPath, Integer durationSec) {
        int duration = durationSec == null ? 0 : durationSec;
        String intent;
        try {
            String text = generationChatClient.prompt()
                    .system(AgentPrompts.INTENT_JUDGE_PROMPT)
                    .user(buildJudgePrompt(userText, duration))
                    .call()
                    .content();
            intent = parseJudgeIntent(text);
        } catch (Exception e) {
            // 余额不足：写 pending（stage=ROUTE，充值后用户回复可继续），返回充值文案
            if (ModelServiceErrors.isBalanceInsufficient(e)) {
                log.warn("意图判定遇到模型服务余额不足, conversationId={}", conversationId);
                rememberPending(conversationId, videoTempPath, durationSec, STAGE_ROUTE);
                return new AskResult(ModelServiceErrors.BALANCE_MESSAGE, null);
            }
            log.warn("意图判定失败，按时长降级, conversationId={}", conversationId, e);
            intent = null;
        }
        if (intent == null || intent.isBlank()) {
            // 判定缺失降级：>30min 只能走建课路径，否则出处理方式卡问一轮
            intent = duration > MAX_TRANSCRIBE_SEC ? "COURSE" : "UNCLEAR";
        }
        return switch (intent.trim().toUpperCase()) {
            case "TRANSCRIBE" -> startTranscription(userId, conversationId, videoTempPath, duration);
            case "COURSE" -> {
                rememberPending(conversationId, videoTempPath, durationSec, STAGE_PROFILE);
                yield profileCard(duration);
            }
            default -> {
                rememberPending(conversationId, videoTempPath, durationSec, STAGE_ROUTE);
                yield routeCard(duration);
            }
        };
    }

    /**
     * 回合2：按 pending.stage 分派。无 pending 时返回 null（交回主对话流程）。
     */
    public AskResult resolveAndExecute(Long userId, Long conversationId, String userText) {
        PendingVideo pending = readPending(conversationId);
        if (pending == null) {
            return null;
        }
        clearPending(conversationId);
        int durationSec = pending.durationSec() == null ? 0 : pending.durationSec();
        if (STAGE_ROUTE.equals(pending.stage())) {
            return resolveRoute(userId, conversationId, userText, pending, durationSec);
        }
        return resolveProfile(userId, conversationId, userText, pending, durationSec);
    }

    // ---- ROUTE 阶段：处理方式卡确定性分派 ----

    /** 处理方式卡回答分派：含「课程」走三问卡、含「转写/提问/总结」直接转写、两可按时长默认（公开静态便于单测） */
    public AskResult resolveRoute(Long userId, Long conversationId, String userText, PendingVideo pending, int durationSec) {
        String text = userText == null ? "" : userText;
        boolean course = text.contains("课程");
        boolean transcribe = text.contains("转写") || text.contains("提问") || text.contains("总结");
        if (course == transcribe) {
            // 都命中或都没命中（含「转写成课程」类混合表述落在自定义文本里）：按时长默认
            course = durationSec > MAX_TRANSCRIBE_SEC;
        }
        if (!course) {
            return startTranscription(userId, conversationId, pending.videoTempPath(), durationSec);
        }
        rememberPending(conversationId, pending.videoTempPath(), pending.durationSec(), STAGE_PROFILE);
        return profileCard(durationSec);
    }

    // ---- PROFILE 阶段：三问解析 → 画像落档 → 建课 ----

    /** 包级可见：PROFILE 阶段分派（同包单测直接调用） */
    AskResult resolveProfile(Long userId, Long conversationId, String userText,
                             PendingVideo pending, int durationSec) {
        String style = STYLE_FOLLOW;
        String goal = null;
        String level = null;
        String gradeLevel = null;
        String reply = null;
        try {
            UserProfile profile = profileService.getByUser(userId);
            String text = generationChatClient.prompt()
                    .system(AgentPrompts.INTENT_RESOLVE_PROMPT)
                    .user(buildResolvePrompt(userText))
                    .call()
                    .content();
            ProfileAnswer answer = parseProfileAnswer(text);
            if (answer == null) {
                throw new IllegalStateException("三问解析输出异常");
            }
            goal = answer.goal();
            level = answer.level();
            style = mapStyle(answer.style());
            gradeLevel = answer.gradeLevel();
            reply = answer.reply();
        } catch (Exception e) {
            // 余额不足：不执行建课（流水线会因同样原因失败），告知充值
            if (ModelServiceErrors.isBalanceInsufficient(e)) {
                log.warn("三问解析遇到模型服务余额不足, conversationId={}", conversationId);
                return new AskResult(ModelServiceErrors.BALANCE_MESSAGE, null);
            }
            log.warn("三问解析失败，按默认跟学型建课, conversationId={}", conversationId, e);
        }
        saveProfilePatch(userId, goal, level, gradeLevel);
        return createCourse(userId, conversationId, pending.videoTempPath(), style, goal, level, reply);
    }

    /** 风格答案归一：FOLLOW/EXAM_SPRINT 直收，中文「考点速查」类说法映射 EXAM_SPRINT，其余默认 FOLLOW（公开静态便于单测） */
    public static String mapStyle(String style) {
        if (style == null || style.isBlank()) {
            return STYLE_FOLLOW;
        }
        return switch (style.trim().toUpperCase()) {
            case "EXAM_SPRINT", "考点速查型", "考点速查", "考点" -> STYLE_EXAM_SPRINT;
            default -> STYLE_FOLLOW;
        };
    }

    // ---- 意图执行 ----

    /** 轻量转写直接提交（不出卡）：文件校验 + 余额/失败文案 */
    private AskResult startTranscription(Long userId, Long conversationId, String videoTempPath, int durationSec) {
        try {
            if (videoTempPath == null || !Files.exists(Path.of(videoTempPath))) {
                return new AskResult("视频临时文件已被清理，麻烦重新上传一次，马上为你处理。", null);
            }
            transcriptionService.submit(userId, conversationId, videoTempPath, durationSec);
            return new AskResult("已开始转写语音（预计 2~5 分钟），完成后全文会自动发到这里。", null);
        } catch (Exception e) {
            if (ModelServiceErrors.isBalanceInsufficient(e)) {
                log.warn("轻量转写遇到模型服务余额不足, conversationId={}", conversationId);
                return new AskResult(ModelServiceErrors.BALANCE_MESSAGE, null);
            }
            log.error("轻量转写提交失败, userId={}, conversationId={}", userId, conversationId, e);
            return new AskResult("处理失败了（" + e.getMessage() + "），请重新上传视频再试一次。", null);
        }
    }

    /** 建课三步（与 CreateCourseFromVideoTool 一致）：标题兜底 → uploadFromLocal（带风格与画像）→ course_task 占位消息 */
    private AskResult createCourse(Long userId, Long conversationId, String tempPath,
                                   String noteStyle, String learnerGoal, String learnerLevel, String reply) {
        Path video = Path.of(tempPath);
        if (!Files.exists(video)) {
            return new AskResult("视频临时文件已被清理，麻烦重新上传一次，马上为你处理。", null);
        }
        // course.title 非空约束：用户没指定标题时回退视频文件名（去 UUID 前缀）
        Course course = courseService.uploadFromLocal(userId, video, titleFromFileName(video), null,
                noteStyle, learnerGoal, learnerLevel);
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
        pushService.pushToUser(userId, ChatEvent.course(placeholder.getId(), "PENDING", null, null, course.getId())
                .conversationId(conversationId));
        log.info("意图 Agent 建课完成, userId={}, conversationId={}, courseId={}, style={}, goal={}, level={}",
                userId, conversationId, course.getId(), noteStyle, learnerGoal, learnerLevel);
        String confirm = "课程《" + title + "》已开始处理（预计几分钟），画面识别、AI 笔记与课后习题的进度会实时显示在对话中，"
                + "完成后会出现课程链接。";
        return new AskResult((StringUtils.hasText(reply) ? reply + "\n\n" : "") + confirm, null);
    }

    // ---- 画像落档 ----

    /** 解析出的画像字段非空才覆盖（避免清掉用户已有画像）；落档失败不阻塞建课 */
    private void saveProfilePatch(Long userId, String goal, String level, String gradeLevel) {
        try {
            UserProfile profile = profileService.getByUser(userId);
            UserProfile target = profile == null ? new UserProfile() : profile;
            boolean changed = false;
            if (StringUtils.hasText(gradeLevel) && !gradeLevel.equals(target.getGradeLevel())) {
                target.setGradeLevel(gradeLevel.trim());
                changed = true;
            }
            if (StringUtils.hasText(goal) && !goal.equals(target.getGoal())) {
                target.setGoal(goal.trim());
                changed = true;
            }
            if (StringUtils.hasText(level) && !level.equals(target.getLevel())) {
                target.setLevel(level.trim());
                changed = true;
            }
            if (changed) {
                profileService.save(userId, target);
                log.info("意图 Agent 画像已更新, userId={}", userId);
            }
        } catch (Exception e) {
            log.warn("画像落档失败, userId={}", userId, e);
        }
    }

    // ---- prompt 组装 ----

    private String buildJudgePrompt(String userText, int durationSec) {
        return "[用户消息]\n" + (userText == null || userText.isBlank() ? "（用户仅上传了视频，未输入文字）" : userText)
                + "\n\n[视频时长]\n" + formatDuration(durationSec);
    }

    private String buildResolvePrompt(String userText) {
        return "[用户回答]\n" + (userText == null || userText.isBlank() ? "（用户仅点选了选项，未补充文字）" : userText);
    }

    // ---- pending 状态（Redis） ----

    private void rememberPending(Long conversationId, String videoTempPath, Integer durationSec, String stage) {
        try {
            redisUtils.setValueTimeout(PENDING_KEY_PREFIX + conversationId,
                    new JSONObject()
                            .set("videoTempPath", videoTempPath)
                            .set("durationSec", durationSec)
                            .set("stage", stage)
                            .toString(),
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
            // 旧格式无 stage 字段（升级过渡期残留）：按 ROUTE 处理，确定性分派可安全兜底
            return new PendingVideo(tempPath, durationSec, obj.getStr("stage", STAGE_ROUTE));
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

    /** 解析意图判定输出（TRANSCRIBE / COURSE / UNCLEAR；容错剥围栏取大括号；公开静态便于单测） */
    public static String parseJudgeIntent(String text) {
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
            String intent = JSONUtil.parseObj(cleaned.substring(start, end + 1)).getStr("intent", null);
            if (intent == null || intent.isBlank()) {
                return null;
            }
            return switch (intent.trim().toUpperCase()) {
                case "TRANSCRIBE", "COURSE", "UNCLEAR" -> intent.trim().toUpperCase();
                default -> null;
            };
        } catch (Exception e) {
            return null;
        }
    }

    /** 解析三问回答的 JSON 输出（容错剥围栏；至少一个业务字段非空才算有效；公开静态便于单测） */
    public static ProfileAnswer parseProfileAnswer(String text) {
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
            ProfileAnswer answer = new ProfileAnswer(
                    obj.getStr("goal", null),
                    obj.getStr("level", null),
                    obj.getStr("style", null),
                    obj.getStr("gradeLevel", null),
                    obj.getStr("reply", null));
            boolean hasAny = StringUtils.hasText(answer.goal()) || StringUtils.hasText(answer.level())
                    || StringUtils.hasText(answer.style()) || StringUtils.hasText(answer.gradeLevel());
            return hasAny ? answer : null;
        } catch (Exception e) {
            return null;
        }
    }

    /** 静态三问卡（建课路径）：目的 / 水平 / 讲解形式 */
    private AskResult profileCard(int durationSec) {
        return cardResult("视频已收到（时长 " + formatDuration(durationSec) + "）。为了让 AI 笔记更贴合你的情况，"
                + "请确认下面三个问题，也可以在每题下方的输入框里用自己的话回答：", PROFILE_QUESTIONS);
    }

    /** 静态处理方式卡（意图不明）：轻量转写 / 做成课程 */
    private AskResult routeCard(int durationSec) {
        return cardResult("视频已收到（时长 " + formatDuration(durationSec) + "）。请选择处理方式："
                + "「轻量转写」快速把语音转成文字稿；「做成课程」则包含画面识别、AI 笔记与课后习题。",
                List.of(new CardQuestion("处理方式", List.of("轻量转写", "做成课程"))));
    }

    /** 卡片 JSON 组装（hutool 不识别 record 访问器，questions 手动构建） */
    private static AskResult cardResult(String message, List<CardQuestion> questions) {
        JSONArray questionArr = new JSONArray();
        for (CardQuestion q : questions) {
            JSONArray opts = new JSONArray();
            q.options().forEach(opts::add);
            questionArr.add(new JSONObject().set("title", q.title()).set("options", opts));
        }
        String cardJson = new JSONObject().set("message", message).set("questions", questionArr).toString();
        return new AskResult(message, cardJson);
    }

    private static String formatDuration(Integer durationSec) {
        if (durationSec == null || durationSec < 0) {
            return "未知";
        }
        return String.format("%02d:%02d", durationSec / 60, durationSec % 60);
    }

    /** 视频文件名 → 课程标题（去上传链路临时文件前缀；与 CreateCourseFromVideoTool 同规则扩展；静态纯函数便于单测） */
    static String titleFromFileName(Path video) {
        String name = video.getFileName().toString();
        int dot = name.lastIndexOf('.');
        String base = dot > 0 ? name.substring(0, dot) : name;
        base = base.replaceFirst("^xj-chat-video-\\d+-?", "").replaceFirst("^[0-9a-fA-F]{8,}-", "")
                .replaceFirst("-\\d{10,}$", "");
        return base.isBlank() ? "未命名课程" : base;
    }
}
