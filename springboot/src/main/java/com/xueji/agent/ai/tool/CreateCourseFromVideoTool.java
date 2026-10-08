package com.xueji.agent.ai.tool;

import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.xueji.agent.domain.entity.Course;
import com.xueji.agent.domain.entity.Message;
import com.xueji.agent.mapper.MessageMapper;
import com.xueji.agent.service.CourseService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;

/**
 * 对话视频创建课程工具（B11 分流）：用户表达「做成课程 / 系统学习」或视频超过 30 分钟时由模型调用。
 * 走完整网课流水线（画面识别 + AI 笔记 + 学习进度）；创建 course_task 占位消息，
 * 流水线各阶段经 AgentEventPushService 按消息 ID 回流进度（工具秒回不阻塞回合）。
 */
@Slf4j
public class CreateCourseFromVideoTool {

    private final CourseService courseService;

    private final MessageMapper messageMapper;

    public CreateCourseFromVideoTool(CourseService courseService, MessageMapper messageMapper) {
        this.courseService = courseService;
        this.messageMapper = messageMapper;
    }

    @Tool(description = "把当前会话中用户上传的视频创建为网课课程，走完整处理流水线（画面识别、AI 笔记、学习进度）。"
            + "当用户表达「做成课程 / 系统学习 / 要 AI 笔记」，或视频时长超过 30 分钟时调用。"
            + "创建成功后告知用户课程已开始处理，预计需要几分钟，进度会实时显示在对话中，完成后可点击链接查看")
    public String createCourseFromVideo(
            @ToolParam(description = "课程标题；仅当用户明确指定了标题时才填写。用户未指定时必须留空，系统会自动取视频文件名作为标题，不要自行拟定", required = false)
            String title,
            @ToolParam(description = "用户对笔记内容的特别要求（如「重点讲原理」「适合考前复习」），用户没有提出就留空", required = false)
            String expectations,
            ToolContext toolContext) {
        Long userId = ((Number) toolContext.getContext().get("userId")).longValue();
        Long conversationId = ((Number) toolContext.getContext().get("conversationId")).longValue();
        // 视频参数解析：优先 ToolContext（当前回合附带）；画像问询等后续回合从会话最近一条视频消息 payload 回查
        //（与 TranscribeVideoTool 同款回查，根治「回答完学段/目标后建课提示重新上传视频」）
        String tempPath = contextStr(toolContext, "videoTempPath");
        if (tempPath == null || tempPath.isBlank()) {
            Message videoMessage = messageMapper.selectOne(new QueryWrapper<Message>()
                    .eq("conversation_id", conversationId)
                    .eq("role", "user")
                    .eq("msg_type", "video")
                    .orderByDesc("id")
                    .last("LIMIT 1"));
            if (videoMessage != null && videoMessage.getPayload() != null && !videoMessage.getPayload().isBlank()) {
                try {
                    tempPath = JSONUtil.parseObj(videoMessage.getPayload()).getStr("videoTempPath", null);
                } catch (Exception e) {
                    log.warn("视频消息 payload 解析失败, messageId={}", videoMessage.getId());
                }
            }
        }
        if (tempPath == null || tempPath.isBlank()) {
            return "CREATE_FAILED: 当前会话没有待处理的视频";
        }
        try {
            Path video = Path.of(tempPath);
            if (!Files.exists(video)) {
                return "CREATE_FAILED: 视频临时文件已丢失（可能已被转写流程清理），请让用户重新上传";
            }
            String courseTitle = isMeaningfulTitle(title) ? title.trim() : titleFromFileName(video);
            Course course = courseService.uploadFromLocal(userId, video, courseTitle, expectations);

            // 课程任务占位消息：流水线各阶段按 message.courseId 定位并更新进度（事实源先落库）
            Message placeholder = new Message()
                    .setConversationId(conversationId)
                    .setRole("assistant")
                    .setMsgType("course_task")
                    .setCourseId(course.getId())
                    .setContent("")
                    .setPayload(new JSONObject()
                            .set("courseId", course.getId())
                            .set("title", courseTitle)
                            .set("status", "processing")
                            .set("stage", "PENDING")
                            .toString())
                    .setCreatedAt(LocalDateTime.now());
            messageMapper.insert(placeholder);
            log.info("课程创建工具执行完成, userId={}, conversationId={}, courseId={}, messageId={}",
                    userId, conversationId, course.getId(), placeholder.getId());
            return "CREATE_OK";
        } catch (Exception e) {
            log.error("课程创建工具执行失败, userId={}, conversationId={}", userId, conversationId, e);
            return "CREATE_FAILED: " + e.getMessage();
        }
    }

    private static String contextStr(ToolContext toolContext, String key) {
        Object value = toolContext.getContext().get(key);
        return value == null ? null : value.toString();
    }

    /** 标题有效性：空 / LLM 模板化默认名（「课程视频（45 分钟）」之类）回退文件名（B26 反馈 bug）；静态纯函数便于单测 */
    static boolean isMeaningfulTitle(String title) {
        if (title == null || title.isBlank()) {
            return false;
        }
        return !title.trim().matches("(课程视频|视频课程).*");
    }

    /** 视频文件名 → 课程标题（静态纯函数便于单测） */
    static String titleFromFileName(Path video) {
        String name = video.getFileName().toString();
        int dot = name.lastIndexOf('.');
        String base = dot > 0 ? name.substring(0, dot) : name;
        // 去掉上传链路的 UUID 前缀、xj-chat-video 临时前缀与常见序号标记
        int dash = base.indexOf('-');
        if (dash > 8 && base.substring(0, dash).matches("[0-9a-fA-F]{8,}")) {
            base = base.substring(dash + 1);
        }
        base = base.replaceFirst("^xj-chat-video-\\d+-?", "").replaceFirst("-\\d{10,}$", "");
        return base.isBlank() ? "未命名课程" : base;
    }
}
