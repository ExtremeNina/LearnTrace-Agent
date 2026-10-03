package com.xueji.agent.ai.tool;

import com.xueji.agent.service.QuestionService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;

/**
 * 保存题目工具：用户在对话中表示要保存题目 / 错题 / 相似题时由模型调用。
 * 模型须先整理好各字段内容（清洗元叙述）再调用；
 * userId / conversationId 经 ToolContext 从调用链路传入；
 * 拍照题目的图片由服务端从 payload 确定性获取，纯文字题目（相似题等）不入库图片。
 */
@Slf4j
public class QuestionSaveTool {

    private final QuestionService questionService;

    public QuestionSaveTool(QuestionService questionService) {
        this.questionService = questionService;
    }

    @Tool(description = "把当前会话中的题目保存到用户的题目记录（拍照识别的题目、生成的相似题、用户手打的题目均可）。当用户表达要保存题目、保存错题、收藏题目、保存刚生成的相似题时调用。调用前必须先整理好各字段内容")
    public String saveQuestion(
            @ToolParam(description = "题目来源：photo=当前会话拍照识别的题目（服务端自动关联最近的题目图片）；text=纯文字题目（生成的相似题、用户手打的题目），不关联图片", required = true)
            String source,
            @ToolParam(description = "整理后的题目原文，只保留题目本身（含条件与问题），不含解答步骤，也不含「题目」等标题词")
            String questionText,
            @ToolParam(description = "整理后的规范解答。去掉「说明：…」「按常见题型补全为…」等元叙述与解释性旁白，只保留面向学生的解题内容")
            String correctAnswer,
            @ToolParam(description = "错因分析；仅在确实识别并分析了用户错因时提供。若没有真实错因可分析（如未识别到用户作答），去掉「没有看到你的作答」这类对话式补充，此参数留空", required = false)
            String analysis,
            @ToolParam(description = "题目所属学科，根据题目内容判断，如：数学 / 语文 / 英语 / 物理 / 化学 / 生物 / 历史 / 地理 / 政治 / 计算机 / 其他", required = false)
            String subject,
            @ToolParam(description = "仅当保存的相似题基于某道已有题目生成时，提供该题 ID（见 rag_search 结果中的题目ID，或对话中【原题】旁标注的来源题目ID）；其余情况留空", required = false)
            Long sourceQuestionId,
            ToolContext toolContext) {
        Long userId = ((Number) toolContext.getContext().get("userId")).longValue();
        Long conversationId = ((Number) toolContext.getContext().get("conversationId")).longValue();
        try {
            boolean saved = questionService.saveFromConversation(userId, conversationId, source, sourceQuestionId,
                    questionText, correctAnswer, analysis, subject);
            log.info("保存题目工具执行完成, userId={}, conversationId={}, source={}, saved={}",
                    userId, conversationId, source, saved);
            return saved ? "SAVE_SUCCESS" : "SAVE_NOT_FOUND";
        } catch (Exception e) {
            log.error("保存题目工具执行失败, userId={}, conversationId={}", userId, conversationId, e);
            return "SAVE_FAILED";
        }
    }
}
