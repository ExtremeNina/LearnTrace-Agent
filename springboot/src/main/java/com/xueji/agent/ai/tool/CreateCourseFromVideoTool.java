package com.xueji.agent.ai.tool;

import cn.hutool.json.JSONObject;
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
            @ToolParam(description = "课程标题；用户未指定时从视频文件名或视频主题拟定", required = false)
            String title,
            @ToolParam(description = "用户对笔记内容的特别要求（如「重点讲原理」「适合考前复习」），用户没有提出就留空", required = false)
            String expectations,
            ToolContext toolContext) {
        Long userId = ((Number) toolContext.getContext().get("userId")).longValue();
        Long conversationId = ((Number) toolContext.getContext().get("conversationId")).longValue();
        Object tempPathObj = toolContext.getContext().get("videoTempPath");
        if (tempPathObj == null) {
            return "CREATE_FAILED: 当前会话没有待处理的视频";
        }
        try {
            Path video = Path.of(tempPathObj.toString());
            if (!Files.exists(video)) {
                return "CREATE_FAILED: 视频临时文件已丢失，请重新上传";
            }
            String courseTitle = title == null || title.isBlank() ? titleFromFileName(video) : title.trim();
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

    private String titleFromFileName(Path video) {
        String name = video.getFileName().toString();
        int dot = name.lastIndexOf('.');
        String base = dot > 0 ? name.substring(0, dot) : name;
        // 去掉上传链路的 UUID 前缀与常见序号标记
        int dash = base.indexOf('-');
        if (dash > 8 && base.substring(0, dash).matches("[0-9a-fA-F]{8,}")) {
            base = base.substring(dash + 1);
        }
        return base.isBlank() ? "未命名课程" : base;
    }
}
