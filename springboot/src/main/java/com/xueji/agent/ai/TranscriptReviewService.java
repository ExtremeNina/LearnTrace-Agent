package com.xueji.agent.ai;

import cn.hutool.json.JSONArray;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.xueji.agent.ai.prompt.AgentPrompts;
import com.xueji.agent.domain.entity.CourseTranscriptSegment;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * 转写审校服务（B26 阶段 1 修正管线的候选生成位）：
 * LLM 利用内置常理（知名实体 / 标准术语 / 常见同音词）扫出疑似 ASR 识别错误。
 * 只做候选生成不做确认——确认权在独立佐证源（帧 OCR 交叉 / 词典 / 重解码，见 TranscriptCorrectionService），
 * 避免「LLM 修正 = 二次猜测写入事实层」。解析容错：代码块围栏 / 缺字段 / 非法项逐条跳过。
 */
@Slf4j
@Service
public class TranscriptReviewService {

    @Resource
    private ChatClient generationChatClient;

    /**
     * 审校转写分段，返回候选修正清单（可能为空）
     */
    public List<CorrectionCandidate> review(List<CourseTranscriptSegment> segments) {
        if (segments == null || segments.isEmpty()) {
            return new ArrayList<>();
        }
        String text = generationChatClient.prompt()
                .system(AgentPrompts.TRANSCRIPT_REVIEW_PROMPT)
                .user(buildReviewPrompt(segments))
                .call()
                .content();
        List<CorrectionCandidate> candidates = parse(text);
        log.info("转写审校完成, 分段数={}, 候选修正数={}", segments.size(), candidates.size());
        return candidates;
    }

    /** 组装审校用户消息：带序号的分段清单。公开静态方法，便于单测 */
    public static String buildReviewPrompt(List<CourseTranscriptSegment> segments) {
        StringBuilder sb = new StringBuilder("[转写分段（序号 | 起止秒 | 文本）]\n");
        for (CourseTranscriptSegment segment : segments) {
            sb.append(segment.getSort()).append(" | ")
                    .append(segment.getStartSec()).append('-').append(segment.getEndSec())
                    .append("s | ").append(segment.getText()).append('\n');
        }
        return sb.toString();
    }

    /**
     * 解析 LLM 输出为候选清单（容错：剥代码块围栏、仅取数组区间、非法项跳过）。
     * 公开静态方法，便于单测
     */
    public static List<CorrectionCandidate> parse(String text) {
        List<CorrectionCandidate> candidates = new ArrayList<>();
        if (text == null || text.isBlank()) {
            return candidates;
        }
        String cleaned = text.replace("```json", "").replace("```", "").trim();
        int start = cleaned.indexOf('[');
        int end = cleaned.lastIndexOf(']');
        if (start < 0 || end <= start) {
            return candidates;
        }
        try {
            JSONArray array = JSONUtil.parseArray(cleaned.substring(start, end + 1));
            for (Object item : array) {
                try {
                    JSONObject obj = (JSONObject) item;
                    int sort = obj.getInt("sort", -1);
                    String original = obj.getStr("original", null);
                    String suggestion = obj.getStr("suggestion", null);
                    String reason = obj.getStr("reason", "");
                    if (sort >= 0 && original != null && !original.isBlank()
                            && suggestion != null && !suggestion.isBlank()) {
                        candidates.add(new CorrectionCandidate(sort, original, suggestion, reason));
                    }
                } catch (Exception e) {
                    log.warn("跳过无法解析的修正候选项: {}", item);
                }
            }
        } catch (Exception e) {
            log.warn("修正候选 JSON 解析失败: {}", cleaned, e);
        }
        return candidates;
    }
}
