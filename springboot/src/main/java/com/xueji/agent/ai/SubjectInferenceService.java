package com.xueji.agent.ai;

import com.xueji.agent.common.Subjects;
import com.xueji.agent.config.ChatClientFactory;
import com.xueji.agent.service.AiModelService;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * 网课学科推断（2026-10-12）：对话建课链路不采集学科（页面上传表单已移除），
 * 意图 Agent 无线索时由本服务基于标题 + 转写摘录做轻量 LLM 判定（供兜底回填任务调用）。
 * 结果经 Subjects 白名单收敛（不在表内 → 其他）。
 */
@Slf4j
@Service
public class SubjectInferenceService {

    private static final String SYSTEM_PROMPT = """
            根据网课标题与转写摘录判断所属学科。只能从：数学、语文、英语、物理、化学、生物、历史、地理、政治、计算机、其他 中选择一个。
            仅输出学科名称本身，不要输出任何其他内容。""";

    @Resource
    private AiModelService aiModelService;

    /**
     * 推断学科（取不到有效结果时返回「其他」，保证回填不反复跑）
     *
     * @param userId           课程归属用户（模型解析链：用户偏好 → 系统默认）
     * @param title            课程标题
     * @param transcriptExcerpt 转写摘录（可为空——无转写时仅按标题判断）
     */
    public String infer(Long userId, String title, String transcriptExcerpt) {
        String user = "网课标题：" + (title == null ? "" : title)
                + "\n转写摘录：" + (transcriptExcerpt == null || transcriptExcerpt.isBlank() ? "（无）" : transcriptExcerpt);
        try {
            String answer = aiModelService
                    .resolve(userId, AiModelService.MODULE_COURSE_NOTE, ChatClientFactory.Variant.GENERATION)
                    .prompt()
                    .system(SYSTEM_PROMPT)
                    .user(user)
                    .call()
                    .content();
            String subject = Subjects.clamp(answer);
            log.info("学科推断完成, userId={}, title={}, subject={}", userId, title, subject);
            return subject == null ? Subjects.OTHER : subject;
        } catch (Exception e) {
            log.error("学科推断失败, userId={}, title={}", userId, title, e);
            throw e;
        }
    }
}
