package com.xueji.agent.ai;

import cn.hutool.json.JSONArray;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.xueji.agent.ai.prompt.AgentPrompts;
import com.xueji.agent.domain.entity.CourseTranscriptSegment;
import com.xueji.agent.utils.RedisUtils;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadPoolExecutor;

/**
 * 内容理解服务（B26 阶段 2 + 自适应分页）：转写文本 → ContentDocument 中间模型。
 * 三级路由：转写 ≤1.5 万字单次直通；超过走 Map（分块并行分析）→ Reduce 合并；
 * Reduce 预估输出 token ≤7500 单次合并，>7500 走分页合并（大纲规划 → 按章并行提取 → 代码汇总），
 * detail 保持完整质量不瘦身——分页让每次输出都远低于 token 上限，截断在结构上不可能发生。
 * 每页产出成功即写 Redis 页缓存（TTL 24h），页失败只重做该页（带错误反馈 ≤2 次），仍失败上抛（快失败）。
 * 输出 JSON 容错解析（剥围栏 / 截断修复 / 逐条跳过非法项）；时间统一为秒并夹取到视频时长内。
 */
@Slf4j
@Service
public class ContentUnderstandingService {

    /** 转写字数超过该阈值时走 Map-Reduce（B26：约 1.5 万字） */
    public static final int MAP_THRESHOLD_CHARS = 15000;

    /** Map 阶段单块的目标字数 */
    private static final int MAP_CHUNK_CHARS = 4000;

    /** 大输出调用的 token 上限（DeepSeek 输出上限 8192；默认 4096 是 course 36 大 JSON 尾部截断的根因之一） */
    private static final int MAX_OUTPUT_TOKENS = 8192;

    /**
     * 分页路由阈值：预估输出 token 超过该值走分页合并。
     * 估算标定（course 36 实测）：输出 token ≈ 0.6 × 转写字符（17,887 字 → 约 10k token，超 8192 被截断）
     */
    public static final int PAGINATION_THRESHOLD_TOKENS = 7500;
    private static final double ESTIMATED_TOKENS_PER_CHAR = 0.6;

    /** 分页页缓存：每页产出成功即存，页失败只重做该页；数据落 MySQL 后由 TTL 自清 */
    private static final String PAGE_CACHE_KEY_PREFIX = "xj_understanding:";
    private static final long PAGE_CACHE_TTL_SEC = 24 * 3600L;

    /** 大输出调用的统一 options（maxTokens 拉满到服务方上限） */
    private OpenAiChatOptions outputOptions() {
        return OpenAiChatOptions.builder().maxTokens(MAX_OUTPUT_TOKENS).build();
    }

    @Resource
    private ChatClient generationChatClient;

    @Resource(name = "courseExecutor")
    private ThreadPoolExecutor courseExecutor;

    @Resource
    private RedisUtils redisUtils;

    /**
     * 内容理解主入口：自适应路由（直通 / Map-Reduce 单次合并 / Map-Reduce 分页合并）
     */
    public ContentUnderstanding understand(Long courseId, List<CourseTranscriptSegment> segments, int durationSec)
            throws java.util.concurrent.ExecutionException, InterruptedException {
        int chars = totalChars(segments);
        if (chars <= MAP_THRESHOLD_CHARS) {
            log.info("内容理解走单次直通, 字数={}", chars);
            return parseWithRetry(buildDirectPrompt(segments, durationSec), "内容理解（直通）", durationSec);
        }

        log.info("内容理解走 Map-Reduce, 字数={}", chars);
        List<List<CourseTranscriptSegment>> chunks = chunkSegments(segments, MAP_CHUNK_CHARS);
        List<Future<String>> futures = new ArrayList<>();
        for (List<CourseTranscriptSegment> chunk : chunks) {
            futures.add(courseExecutor.submit(() ->
                    callLlm(AgentPrompts.CONTENT_MAP_PROMPT, buildMapPrompt(chunk))));
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

        int estimatedTokens = (int) Math.round(chars * ESTIMATED_TOKENS_PER_CHAR);
        if (estimatedTokens <= PAGINATION_THRESHOLD_TOKENS) {
            log.info("Reduce 合并预估输出 {} token（≤{}），走单次合并", estimatedTokens, PAGINATION_THRESHOLD_TOKENS);
            return parseWithRetry(buildReducePrompt(mapParts, durationSec), "内容理解（Reduce 合并）", durationSec);
        }

        log.info("Reduce 合并预估输出 {} token（>{}），走分页合并, courseId={}",
                estimatedTokens, PAGINATION_THRESHOLD_TOKENS, courseId);
        return paginatedUnderstanding(courseId, mapParts, durationSec);
    }

    /**
     * 分页合并（长视频大 JSON 截断的根治）：
     * 第 1 页·大纲规划（章节 + 每章覆盖的块，小输出）→ 第 2~N 页·按章并行提取该章知识点
     * （detail 保持完整质量不瘦身，每页 1~2 千字符远低于输出上限）→ 代码汇总 + 边界推导。
     * 每页产出成功即写 Redis 页缓存，页解析失败带反馈重试 ≤2 次后上抛（快失败）；
     * 全部页齐后清理页缓存（数据已落 MySQL，Redis 仅草稿）
     */
    private ContentUnderstanding paginatedUnderstanding(Long courseId, List<String> mapParts, int durationSec) {
        JSONObject outline = JSONUtil.parseObj(jsonCallWithFeedbackRetry(
                AgentPrompts.CONTENT_OUTLINE_PROMPT, buildOutlinePrompt(mapParts, durationSec), "理解大纲规划"));

        ContentUnderstanding result = new ContentUnderstanding();
        result.title = outline.getStr("title", "未命名内容");
        result.summary = outline.getStr("summary", "");

        JSONArray sectionArray = outline.getJSONArray("sections");
        if (sectionArray == null || sectionArray.isEmpty()) {
            throw new IllegalStateException("理解大纲未产出章节");
        }

        List<ContentUnderstanding.Section> sections = new ArrayList<>();
        List<ContentUnderstanding.KnowledgePoint> points = new ArrayList<>();
        List<String> cacheKeys = new ArrayList<>();
        int chapterSort = 0;
        for (Object item : sectionArray) {
            JSONObject chapter;
            try {
                chapter = (JSONObject) item;
            } catch (Exception e) {
                log.warn("跳过无法解析的大纲章节项: {}", item);
                continue;
            }
            String title = chapter.getStr("title", null);
            if (title == null || title.isBlank()) {
                continue;
            }
            ContentUnderstanding.Section section = new ContentUnderstanding.Section();
            section.title = title.trim();
            section.summary = chapter.getStr("summary", "");
            sections.add(section);

            String chapterUserPrompt = buildChapterPrompt(section.title,
                    pickMapParts(mapParts, chapter.getJSONArray("chunks")));
            String cacheKey = PAGE_CACHE_KEY_PREFIX + courseId + ":page:" + chapterSort;
            cacheKeys.add(cacheKey);
            String pageJson = chapterPageWithCache(courseId, chapterSort, cacheKey,
                    AgentPrompts.CONTENT_CHAPTER_PROMPT, chapterUserPrompt);

            JSONArray pagePoints = JSONUtil.parseObj(pageJson).getJSONArray("knowledgePoints");
            if (pagePoints != null) {
                for (Object pointItem : pagePoints) {
                    try {
                        JSONObject o = (JSONObject) pointItem;
                        String name = o.getStr("name", null);
                        if (name == null || name.isBlank()) {
                            continue;
                        }
                        ContentUnderstanding.KnowledgePoint point = new ContentUnderstanding.KnowledgePoint();
                        point.name = name.trim();
                        point.detail = o.getStr("detail", "");
                        point.timeSec = clampSec(o.getInt("time", null), durationSec);
                        point.sectionSort = chapterSort;
                        point.important = o.getBool("important", false);
                        point.errorProne = o.getBool("errorProne", false);
                        points.add(point);
                    } catch (Exception e) {
                        log.warn("跳过无法解析的章节知识点项: {}", pointItem);
                    }
                }
            }
            chapterSort++;
        }
        if (sections.isEmpty()) {
            throw new IllegalStateException("理解大纲未产出有效章节");
        }
        result.sections = sections;
        result.knowledgePoints = points;

        ContentUnderstanding finalized = finalizeUnderstanding(result, durationSec);
        // 数据将落 MySQL：页缓存（草稿）清理；失败由 TTL 自清兜底
        try {
            redisUtils.delKeys(cacheKeys);
        } catch (Exception e) {
            log.warn("分页页缓存清理失败（TTL 自清兜底）, courseId={}", courseId, e);
        }
        return finalized;
    }

    /** 章节页获取：优先 Redis 页缓存（页失败只重做该页的基础）；未命中走 LLM（带反馈重试 ≤2 次）并回写缓存 */
    private String chapterPageWithCache(Long courseId, int pageIndex, String cacheKey,
                                        String systemPrompt, String userPrompt) {
        try {
            Object cached = redisUtils.getValue(cacheKey);
            if (cached instanceof String cachedText && !cachedText.isBlank()) {
                log.info("章节页命中 Redis 页缓存, courseId={}, page={}", courseId, pageIndex);
                return cachedText;
            }
        } catch (Exception e) {
            log.warn("章节页缓存读取失败（跳过缓存）, courseId={}, page={}", courseId, pageIndex, e);
        }
        String pageJson = jsonCallWithFeedbackRetry(systemPrompt, userPrompt,
                "章节知识点提取（第 " + (pageIndex + 1) + " 章）");
        try {
            redisUtils.setValueTimeout(cacheKey, pageJson, PAGE_CACHE_TTL_SEC);
        } catch (Exception e) {
            log.warn("章节页缓存写入失败, courseId={}, page={}", courseId, pageIndex, e);
        }
        return pageJson;
    }

    /** 从大纲的 chunks 序号（1-based）挑选该章覆盖的 map 块；缺失/为空时回退全部块（保证信息完整） */
    static String pickMapParts(List<String> mapParts, JSONArray chunks) {
        if (chunks == null || chunks.isEmpty()) {
            return String.join("\n", mapParts);
        }
        StringBuilder sb = new StringBuilder();
        for (Object item : chunks) {
            int index;
            try {
                index = Integer.parseInt(String.valueOf(item).trim());
            } catch (NumberFormatException e) {
                continue;
            }
            if (index >= 1 && index <= mapParts.size()) {
                sb.append(mapParts.get(index - 1)).append('\n');
            }
        }
        return sb.length() == 0 ? String.join("\n", mapParts) : sb.toString();
    }

    /** 统一收尾（单次合并/分页共用）：均匀切分检测 + 章节边界推导 + 无定位章节过滤与归属重排 */
    private static ContentUnderstanding finalizeUnderstanding(ContentUnderstanding result, int durationSec) {
        List<ContentUnderstanding.Section> sections = result.sections;
        List<ContentUnderstanding.KnowledgePoint> points = result.knowledgePoints;
        // LLM 原始章节时间可能虚构（长视频 Reduce 阶段无内容级时间锚点，易退化为按时长均匀切分）：
        // 先检测虚构特征，再用知识点真实时间重推导边界（时间戳永远锚定转写真实秒数）
        String uniformIssue = detectUniformSplitIssue(sections);
        deriveSectionBoundaries(result, durationSec);
        // 新语义下 LLM 可省略 start/end（由系统推导）：推导后仍无边界的章节无法定位，丢弃并重排知识点归属
        List<ContentUnderstanding.Section> located = new ArrayList<>();
        List<Integer> oldToNew = new ArrayList<>();
        for (ContentUnderstanding.Section section : sections) {
            if (section.startSec != null && section.endSec != null) {
                oldToNew.add(located.size());
                located.add(section);
            } else {
                oldToNew.add(-1);
            }
        }
        if (located.size() != sections.size()) {
            for (ContentUnderstanding.KnowledgePoint point : points) {
                int old = point.sectionSort == null ? 0 : point.sectionSort;
                int mapped = old >= 0 && old < oldToNew.size() ? oldToNew.get(old) : -1;
                point.sectionSort = mapped >= 0 ? mapped : Math.max(0, located.size() - 1);
            }
        }
        result.sections = located;
        if (uniformIssue != null) {
            result.issue = uniformIssue + (hasAnchorTimes(points)
                    ? "；已按知识点真实时间重推导章节边界"
                    : "；知识点缺少真实时间锚点，边界未能修正，建议重新生成内容理解");
        }
        return result;
    }

    /**
     * LLM 调用 + 解析（直通与单次合并共用），解析失败带反馈重试一次：
     * 坏 JSON 带错误位置反馈重写一次即可恢复；仍失败抛出由调用方（Graph → 流水线）快速终止
     */
    private ContentUnderstanding parseWithRetry(String userPrompt, String stage, int durationSec) {
        String prompt = userPrompt;
        Exception last = null;
        for (int attempt = 1; attempt <= 2; attempt++) {
            try {
                return parseUnderstanding(callLlm(AgentPrompts.CONTENT_UNDERSTAND_PROMPT, prompt), durationSec);
            } catch (Exception e) {
                last = e;
                log.warn("{} 输出解析失败（第 {} 次）, 错误={}", stage, attempt, e.getMessage());
                prompt = userPrompt + "\n\n[重试反馈] 上一次输出的 JSON 非法（" + e.getMessage()
                        + "），请重新输出完整合法的 JSON：不得省略任何字段、不得截断内容、不得附加解释文字。";
            }
        }
        throw new IllegalStateException(stage + "失败：" + last.getMessage(), last);
    }

    /** JSON 输出调用 + 校验（大纲/章节页共用）：解析失败带反馈重试 ≤2 次，返回合法 JSON 文本 */
    private String jsonCallWithFeedbackRetry(String systemPrompt, String userPrompt, String stage) {
        String prompt = userPrompt;
        Exception last = null;
        for (int attempt = 1; attempt <= 2; attempt++) {
            try {
                return extractValidJson(callLlm(systemPrompt, prompt), stage);
            } catch (Exception e) {
                last = e;
                log.warn("{} 输出解析失败（第 {} 次）, 错误={}", stage, attempt, e.getMessage());
                prompt = userPrompt + "\n\n[重试反馈] 上一次输出的 JSON 非法（" + e.getMessage()
                        + "），请重新输出完整合法的 JSON：不得省略任何字段、不得截断内容、不得附加解释文字。";
            }
        }
        throw new IllegalStateException(stage + "失败：" + last.getMessage(), last);
    }

    private String callLlm(String systemPrompt, String userPrompt) {
        return generationChatClient.prompt()
                .system(systemPrompt)
                .user(userPrompt)
                .options(outputOptions())
                .call()
                .content();
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

    /** 大纲规划用户消息：各块分析结果 JSON + 视频时长（只归类，不输出知识点细节） */
    public static String buildOutlinePrompt(List<String> mapParts, int durationSec) {
        StringBuilder sb = new StringBuilder();
        sb.append("视频时长：").append(durationSec).append(" 秒。\n\n");
        sb.append("以下是对长视频逐块分析的 JSON 结果（range 为该块时间范围），请规划章节大纲：\n");
        for (String part : mapParts) {
            sb.append(part).append('\n');
        }
        return sb.toString();
    }

    /** 按章提取用户消息：该章覆盖的逐块分析 JSON（detail 保持完整质量） */
    public static String buildChapterPrompt(String chapterTitle, String chunkText) {
        StringBuilder sb = new StringBuilder();
        sb.append("章节：《").append(chapterTitle).append("》\n\n");
        sb.append("以下是该章节覆盖的逐块分析 JSON 结果，请整理输出该章节的知识点：\n");
        sb.append(chunkText);
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

    /** 大纲章节（解析中间模型）：title/summary + 覆盖的块序号（1-based） */
    public static class OutlineChapter {
        String title;
        String summary;
        List<Integer> chunkIndexes;

        public String getTitle() {
            return title;
        }

        public String getSummary() {
            return summary;
        }

        public List<Integer> getChunkIndexes() {
            return chunkIndexes;
        }
    }

    /**
     * 大纲页解析（公开静态便于单测）：{title, summary, sections:[{title, summary, chunks:[...]}]}；
     * 非法项逐条跳过，chunks 缺省为空列表（汇总时回退全部块）
     */
    public static List<OutlineChapter> parseOutline(String text) {
        List<OutlineChapter> chapters = new ArrayList<>();
        JSONObject obj = JSONUtil.parseObj(extractValidJson(text, "理解大纲规划"));
        JSONArray sectionArray = obj.getJSONArray("sections");
        if (sectionArray == null) {
            return chapters;
        }
        for (Object item : sectionArray) {
            try {
                JSONObject o = (JSONObject) item;
                String title = o.getStr("title", null);
                if (title == null || title.isBlank()) {
                    continue;
                }
                OutlineChapter chapter = new OutlineChapter();
                chapter.title = title.trim();
                chapter.summary = o.getStr("summary", "");
                List<Integer> chunkIndexes = new ArrayList<>();
                JSONArray chunks = o.getJSONArray("chunks");
                if (chunks != null) {
                    for (Object chunk : chunks) {
                        try {
                            chunkIndexes.add(Integer.parseInt(String.valueOf(chunk).trim()));
                        } catch (NumberFormatException ignored) {
                            // 非法块序号跳过
                        }
                    }
                }
                chapter.chunkIndexes = chunkIndexes;
                chapters.add(chapter);
            } catch (Exception e) {
                log.warn("跳过无法解析的大纲章节项: {}", item);
            }
        }
        return chapters;
    }

    /**
     * 章节页解析（公开静态便于单测）：{knowledgePoints:[{name, detail, time, important, errorProne}]}；
     * 时间夹取到 [0, durationSec]，非法项跳过，sectionSort 由调用方按章设置
     */
    public static List<ContentUnderstanding.KnowledgePoint> parseChapterPoints(String text, int durationSec) {
        List<ContentUnderstanding.KnowledgePoint> points = new ArrayList<>();
        JSONObject obj = JSONUtil.parseObj(extractValidJson(text, "章节知识点提取"));
        JSONArray pointArray = obj.getJSONArray("knowledgePoints");
        if (pointArray == null) {
            return points;
        }
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
                point.important = o.getBool("important", false);
                point.errorProne = o.getBool("errorProne", false);
                points.add(point);
            } catch (Exception e) {
                log.warn("跳过无法解析的章节知识点项: {}", item);
            }
        }
        return points;
    }

    /**
     * 从 LLM 输出提取合法 JSON 文本（剥围栏 / 取对象区间 / 截断修复）；
     * 公开静态便于单测与复用。空输出 / 不含 JSON 对象 / 结构错乱且无法修复时抛 IllegalStateException
     */
    public static String extractValidJson(String text, String stage) {
        if (text == null || text.isBlank()) {
            throw new IllegalStateException(stage + "未返回内容");
        }
        String cleaned = text.replace("```json", "").replace("```", "").trim();
        int start = cleaned.indexOf('{');
        int end = cleaned.lastIndexOf('}');
        if (start < 0 || end <= start) {
            throw new IllegalStateException(stage + "输出不含 JSON 对象");
        }
        String raw = cleaned.substring(start, end + 1);
        try {
            JSONUtil.parseObj(raw);
            return raw;
        } catch (cn.hutool.json.JSONException e) {
            // 大 JSON 常见截断（输出 token 上限）：修复——截到最后一个完整对象、补齐未闭合括号，丢弃残缺尾项
            String salvaged = salvageTruncatedJson(raw);
            if (salvaged == null) {
                throw e;
            }
            log.warn("{} 输出 JSON 不完整（{}），已截断修复并丢弃残缺尾项", stage, e.getMessage());
            return salvaged;
        }
    }

    /**
     * 解析 LLM 输出为 ContentUnderstanding（容错：剥代码块围栏、取对象区间、逐条跳过非法项；
     * 时间夹取到 [0, durationSec]，end < start 时交换；截断自动修复）
     */
    public static ContentUnderstanding parseUnderstanding(String text, int durationSec) {
        JSONObject obj = JSONUtil.parseObj(extractValidJson(text, "内容理解"));

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
                    if (section.title == null || section.title.isBlank()) {
                        continue;
                    }
                    if (section.startSec != null && section.endSec != null && section.endSec < section.startSec) {
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
        return finalizeUnderstanding(result, durationSec);
    }

    /**
     * 章节边界代码推导（时间戳锚定真实转写，杜绝 LLM 虚构）：
     * LLM 只负责归类（知识点 sectionSort），边界切点 = 归组内知识点 timeSec 的最早值，
     * 无锚点章节的切点按相邻已知切点线性插值兜底；首切点 0、末切点视频时长，章节首尾衔接平铺全片。
     * LLM 归组错序时单调保护退化为零长章节（边界仍真实）；完全无锚点时保留 LLM 原边界
     */
    static void deriveSectionBoundaries(ContentUnderstanding result, int durationSec) {
        List<ContentUnderstanding.Section> sections = result.sections;
        List<ContentUnderstanding.KnowledgePoint> points = result.knowledgePoints;
        if (sections == null || sections.isEmpty()) {
            return;
        }
        int n = sections.size();
        List<List<Integer>> anchors = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            anchors.add(new ArrayList<>());
        }
        if (points != null) {
            for (ContentUnderstanding.KnowledgePoint point : points) {
                int sort = point.sectionSort == null ? 0 : point.sectionSort;
                if (sort >= 0 && sort < n && point.timeSec != null && point.timeSec >= 0) {
                    anchors.get(sort).add(point.timeSec);
                }
            }
        }
        boolean anyAnchor = false;
        for (List<Integer> group : anchors) {
            if (!group.isEmpty()) {
                anyAnchor = true;
                break;
            }
        }
        if (!anyAnchor) {
            return;
        }
        // 边界切点：cuts[0]=0、cuts[n]=时长，已知锚点章节以其最早知识点时间为切点
        int maxSec = Math.max(durationSec, 0);
        int[] cuts = new int[n + 1];
        boolean[] known = new boolean[n + 1];
        cuts[0] = 0;
        known[0] = true;
        cuts[n] = maxSec;
        known[n] = true;
        for (int i = 1; i < n; i++) {
            List<Integer> group = anchors.get(i);
            if (!group.isEmpty()) {
                int min = Integer.MAX_VALUE;
                for (Integer t : group) {
                    if (t != null && t < min) {
                        min = t;
                    }
                }
                if (min != Integer.MAX_VALUE) {
                    cuts[i] = Math.min(min, maxSec);
                    known[i] = true;
                }
            }
        }
        // 未知切点按最近已知切点线性插值
        int left = 0;
        for (int i = 1; i <= n; i++) {
            if (known[i]) {
                for (int k = left + 1; k < i; k++) {
                    cuts[k] = cuts[left] + (int) Math.round(
                            (double) (cuts[i] - cuts[left]) * (k - left) / (i - left));
                }
                left = i;
            }
        }
        // 单调保护
        for (int i = 1; i <= n; i++) {
            if (cuts[i] < cuts[i - 1]) {
                cuts[i] = cuts[i - 1];
            }
        }
        for (int i = 0; i < n; i++) {
            sections.get(i).startSec = cuts[i];
            sections.get(i).endSec = cuts[i + 1];
        }
    }

    /**
     * 均匀切分检测（纯函数）：≥3 章且相邻章节起点间隔完全相等 → LLM 按时长均分虚构的强特征
     * （真实课程章节间隔完全相等可忽略）。NULL = 未命中
     */
    static String detectUniformSplitIssue(List<ContentUnderstanding.Section> sections) {
        if (sections == null || sections.size() < 3) {
            return null;
        }
        Integer prev = null;
        Integer gap = null;
        for (ContentUnderstanding.Section section : sections) {
            if (section.startSec == null) {
                return null;
            }
            if (prev != null) {
                int d = section.startSec - prev;
                if (d <= 0 || (gap != null && gap != d)) {
                    return null;
                }
                gap = d;
            }
            prev = section.startSec;
        }
        return "内容理解章节时间呈均匀切分（相邻间隔均为 " + gap + "s，疑似虚构）";
    }

    private static boolean hasAnchorTimes(List<ContentUnderstanding.KnowledgePoint> points) {
        if (points != null) {
            for (ContentUnderstanding.KnowledgePoint point : points) {
                if (point.timeSec != null) {
                    return true;
                }
            }
        }
        return false;
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

    /**
     * 截断 JSON 修复（公开静态便于单测）：输出 token 上限导致的截断特征是"尾部断裂"——
     * 扫描括号配对（跳过字符串字面量），定位最后一个完整闭合的位置，截断到该处并按栈补齐未闭合的 '[' / '{'，
     * 使被截断的输出可解析（丢弃残缺尾项）。完整 JSON（无未闭合括号）或结构本身错乱（非单纯截断）返回 NULL，
     * 交回调用方走既有重试/失败路径
     */
    public static String salvageTruncatedJson(String json) {
        java.util.Deque<Character> stack = new java.util.ArrayDeque<>();
        boolean inString = false;
        boolean escaped = false;
        int lastSafe = -1;
        for (int i = 0; i < json.length(); i++) {
            char ch = json.charAt(i);
            if (inString) {
                if (escaped) {
                    escaped = false;
                } else if (ch == '\\') {
                    escaped = true;
                } else if (ch == '"') {
                    inString = false;
                }
                continue;
            }
            if (ch == '"') {
                inString = true;
            } else if (ch == '{' || ch == '[') {
                stack.push(ch);
            } else if (ch == '}' || ch == ']') {
                if (stack.isEmpty()
                        || !((ch == '}' && stack.peek() == '{') || (ch == ']' && stack.peek() == '['))) {
                    return null;
                }
                stack.pop();
                lastSafe = i;
            }
        }
        if (stack.isEmpty()) {
            return null;
        }
        String cut;
        if (lastSafe >= 0) {
            // 截到最后一个完整闭合的对象（其后残缺尾项丢弃）
            cut = json.substring(0, lastSafe + 1);
        } else {
            // 未出现任何完整闭合（如根对象内直接断）：截到输入末尾并修整悬挂状态
            cut = json;
            if (inString) {
                cut = cut + "\"";
            }
            String trimmed = cut.trim();
            if (trimmed.endsWith(":")) {
                cut = trimmed + " null";
            } else if (trimmed.endsWith(",")) {
                cut = trimmed.substring(0, trimmed.length() - 1);
            }
        }
        // 关键：对"截断后的文本"重扫栈状态——全量扫描的栈包含 lastSafe 之后已丢弃内容的未闭合括号，
        // 直接用会导致补出的括号错序（course 36 排查中发现的实现缺陷）
        java.util.Deque<Character> cutStack = new java.util.ArrayDeque<>();
        boolean cutInString = false;
        boolean cutEscaped = false;
        for (int i = 0; i < cut.length(); i++) {
            char ch = cut.charAt(i);
            if (cutInString) {
                if (cutEscaped) {
                    cutEscaped = false;
                } else if (ch == '\\') {
                    cutEscaped = true;
                } else if (ch == '"') {
                    cutInString = false;
                }
                continue;
            }
            if (ch == '"') {
                cutInString = true;
            } else if (ch == '{' || ch == '[') {
                cutStack.push(ch);
            } else if (ch == '}' || ch == ']') {
                if (cutStack.isEmpty()
                        || !((ch == '}' && cutStack.peek() == '{') || (ch == ']' && cutStack.peek() == '['))) {
                    return null;
                }
                cutStack.pop();
            }
        }
        if (cutStack.isEmpty()) {
            return null;
        }
        StringBuilder sb = new StringBuilder(cut);
        while (!cutStack.isEmpty()) {
            Character open = cutStack.pop();
            sb.append(open == '{' ? '}' : ']');
        }
        String repaired = sb.toString();
        try {
            JSONUtil.parseObj(repaired);
            return repaired;
        } catch (Exception e) {
            return null;
        }
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
