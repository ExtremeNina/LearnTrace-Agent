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
import java.util.concurrent.Future;
import java.util.concurrent.ThreadPoolExecutor;

/**
 * 内容理解服务（B26 阶段 2）：转写文本 → ContentDocument 中间模型。
 * 自适应：转写 ≤ 阈值单次直通；超过则 Map（分块并行分析）→ Reduce（合并出最终结构）。
 * 输出 JSON 容错解析（剥代码块围栏 / 逐条跳过非法项）；时间统一为秒并夹取到视频时长内。
 */
@Slf4j
@Service
public class ContentUnderstandingService {

    /** 转写字数超过该阈值时走 Map-Reduce（B26：约 1.5 万字） */
    public static final int MAP_THRESHOLD_CHARS = 15000;

    /** Map 阶段单块的目标字数 */
    private static final int MAP_CHUNK_CHARS = 4000;

    @Resource
    private ChatClient generationChatClient;

    @Resource(name = "courseExecutor")
    private ThreadPoolExecutor courseExecutor;

    /**
     * 内容理解主入口：自适应单次直通 / Map-Reduce
     */
    public ContentUnderstanding understand(List<CourseTranscriptSegment> segments, int durationSec)
            throws java.util.concurrent.ExecutionException, InterruptedException {
        int chars = totalChars(segments);
        if (chars <= MAP_THRESHOLD_CHARS) {
            log.info("内容理解走单次直通, 字数={}", chars);
            String text = generationChatClient.prompt()
                    .system(AgentPrompts.CONTENT_UNDERSTAND_PROMPT)
                    .user(buildDirectPrompt(segments, durationSec))
                    .call()
                    .content();
            return parseUnderstanding(text, durationSec);
        }

        log.info("内容理解走 Map-Reduce, 字数={}", chars);
        List<List<CourseTranscriptSegment>> chunks = chunkSegments(segments, MAP_CHUNK_CHARS);
        List<Future<String>> futures = new ArrayList<>();
        for (List<CourseTranscriptSegment> chunk : chunks) {
            futures.add(courseExecutor.submit(() ->
                    generationChatClient.prompt()
                            .system(AgentPrompts.CONTENT_MAP_PROMPT)
                            .user(buildMapPrompt(chunk))
                            .call()
                            .content()));
        }
        List<String> mapParts = new ArrayList<>();
        for (int i = 0; i < futures.size(); i++) {
            String mapJson = futures.get(i).get();
            CourseTranscriptSegment first = chunks.get(i).get(0);
            CourseTranscriptSegment last = chunks.get(i).get(chunks.get(i).size() - 1);
            mapParts.add(new JSONObject()
                    .set("range", "[" + first.getStartSec() + "-" + last.getEndSec() + "s]")
                    .set("analysis", safeJson(mapJson))
                    .toString());
        }
        String text = generationChatClient.prompt()
                .system(AgentPrompts.CONTENT_UNDERSTAND_PROMPT)
                .user(buildReducePrompt(mapParts, durationSec))
                .call()
                .content();
        return parseUnderstanding(text, durationSec);
    }

    /** 直接模式用户消息：带时间戳的分段全文 + 视频时长 */
    public static String buildDirectPrompt(List<CourseTranscriptSegment> segments, int durationSec) {
        StringBuilder sb = new StringBuilder();
        sb.append("视频时长：").append(durationSec).append(" 秒。\n\n");
        sb.append("[转写分段（含起止秒）]\n");
        for (CourseTranscriptSegment segment : segments) {
            appendSegmentLine(sb, segment);
        }
        return sb.toString();
    }

    /** Map 模式单块用户消息 */
    public static String buildMapPrompt(List<CourseTranscriptSegment> chunk) {
        StringBuilder sb = new StringBuilder("[转写分段（含起止秒）]\n");
        for (CourseTranscriptSegment segment : chunk) {
            appendSegmentLine(sb, segment);
        }
        return sb.toString();
    }

    /** Reduce 模式用户消息：各块分析结果 JSON + 视频时长 */
    public static String buildReducePrompt(List<String> mapParts, int durationSec) {
        StringBuilder sb = new StringBuilder();
        sb.append("视频时长：").append(durationSec).append(" 秒。\n\n");
        sb.append("以下是对长视频逐块分析的 JSON 结果（range 为该块时间范围），请合并为最终 ContentDocument：\n");
        for (String part : mapParts) {
            sb.append(part).append('\n');
        }
        return sb.toString();
    }

    private static void appendSegmentLine(StringBuilder sb, CourseTranscriptSegment segment) {
        sb.append("[").append(segment.getStartSec()).append("-").append(segment.getEndSec()).append("s] ")
                .append(segment.getText()).append('\n');
    }

    /** 按 maxChars 聚合分段为语义块（单段超长时独立成块，允许超限） */
    public static List<List<CourseTranscriptSegment>> chunkSegments(List<CourseTranscriptSegment> segments, int maxChars) {
        List<List<CourseTranscriptSegment>> chunks = new ArrayList<>();
        List<CourseTranscriptSegment> current = new ArrayList<>();
        int currentChars = 0;
        for (CourseTranscriptSegment segment : segments) {
            int len = segment.getText() == null ? 0 : segment.getText().length();
            if (currentChars > 0 && currentChars + len > maxChars) {
                chunks.add(current);
                current = new ArrayList<>();
                currentChars = 0;
            }
            current.add(segment);
            currentChars += len;
        }
        if (!current.isEmpty()) {
            chunks.add(current);
        }
        return chunks;
    }

    /**
     * 解析 LLM 输出为 ContentUnderstanding（容错：剥代码块围栏、取对象区间、逐条跳过非法项；
     * 时间夹取到 [0, durationSec]，end < start 时交换）
     */
    public static ContentUnderstanding parseUnderstanding(String text, int durationSec) {
        if (text == null || text.isBlank()) {
            throw new IllegalStateException("内容理解未返回内容");
        }
        String cleaned = text.replace("```json", "").replace("```", "").trim();
        int start = cleaned.indexOf('{');
        int end = cleaned.lastIndexOf('}');
        if (start < 0 || end <= start) {
            throw new IllegalStateException("内容理解输出不含 JSON 对象");
        }
        JSONObject obj = JSONUtil.parseObj(cleaned.substring(start, end + 1));

        ContentUnderstanding result = new ContentUnderstanding();
        result.title = obj.getStr("title", "未命名内容");
        result.summary = obj.getStr("summary", "");

        List<ContentUnderstanding.Section> sections = new ArrayList<>();
        JSONArray sectionArray = obj.getJSONArray("sections");
        if (sectionArray != null) {
            for (Object item : sectionArray) {
                try {
                    JSONObject o = (JSONObject) item;
                    ContentUnderstanding.Section section = new ContentUnderstanding.Section();
                    section.title = o.getStr("title", null);
                    section.summary = o.getStr("summary", "");
                    section.startSec = clampSec(o.getInt("start", null), durationSec);
                    section.endSec = clampSec(o.getInt("end", null), durationSec);
                    if (section.title == null || section.title.isBlank()
                            || section.startSec == null || section.endSec == null) {
                        continue;
                    }
                    if (section.endSec < section.startSec) {
                        int swap = section.startSec;
                        section.startSec = section.endSec;
                        section.endSec = swap;
                    }
                    sections.add(section);
                } catch (Exception e) {
                    log.warn("跳过无法解析的章节项: {}", item);
                }
            }
        }
        result.sections = sections;

        List<ContentUnderstanding.KnowledgePoint> points = new ArrayList<>();
        JSONArray pointArray = obj.getJSONArray("knowledgePoints");
        if (pointArray != null) {
            for (Object item : pointArray) {
                try {
                    JSONObject o = (JSONObject) item;
                    String name = o.getStr("name", null);
                    if (name == null || name.isBlank()) {
                        continue;
                    }
                    ContentUnderstanding.KnowledgePoint point = new ContentUnderstanding.KnowledgePoint();
                    point.name = name.trim();
                    point.detail = o.getStr("detail", "");
                    point.timeSec = clampSec(o.getInt("time", null), durationSec);
                    int sectionSort = o.getInt("section", 1);
                    point.sectionSort = Math.max(1, sectionSort) - 1;
                    point.important = o.getBool("important", false);
                    point.errorProne = o.getBool("errorProne", false);
                    points.add(point);
                } catch (Exception e) {
                    log.warn("跳过无法解析的知识点项: {}", item);
                }
            }
        }
        result.knowledgePoints = points;
        return result;
    }

    private static Integer clampSec(Integer sec, int durationSec) {
        if (sec == null || sec < 0) {
            return null;
        }
        if (durationSec > 0 && sec > durationSec) {
            return durationSec;
        }
        return sec;
    }

    /** Map 结果容错解析（非法 JSON 转为纯文本摘要，避免 Reduce 断链） */
    private static String safeJson(String mapJson) {
        if (mapJson == null || mapJson.isBlank()) {
            return "{\"summary\": \"（本块分析失败）\", \"points\": []}";
        }
        String cleaned = mapJson.replace("```json", "").replace("```", "").trim();
        int start = cleaned.indexOf('{');
        int end = cleaned.lastIndexOf('}');
        if (start < 0 || end <= start) {
            return "{\"summary\": \"（本块分析输出异常）\", \"points\": []}";
        }
        try {
            JSONUtil.parseObj(cleaned.substring(start, end + 1));
            return cleaned.substring(start, end + 1);
        } catch (Exception e) {
            return "{\"summary\": \"（本块分析输出异常）\", \"points\": []}";
        }
    }

    private static int totalChars(List<CourseTranscriptSegment> segments) {
        int chars = 0;
        for (CourseTranscriptSegment segment : segments) {
            chars += segment.getText() == null ? 0 : segment.getText().length();
        }
        return chars;
    }
}
