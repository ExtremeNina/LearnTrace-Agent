package com.xueji.agent.ai.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;

import java.util.ArrayList;
import java.util.List;

/**
 * 语音转写工具（PRD §3.2 网课转写）：阿里云百炼 Qwen-Audio-3.1-ASR-Flash，
 * DashScope 多模态生成端点，传入音频 URL 返回转写文本（网课流水线的音频通道）。
 * 该端点实际返回句级结构（text / sentence / words，带毫秒时间戳），
 * transcribeSegments() 保留句级时间戳供转写对照与时间轴对齐使用。
 * Bean 装配在 SpringAIConfig 中完成（构造参数来自 qwen.asr.* 配置）。
 */
@Slf4j
public class QwenAsrTool {

    private final String baseUrl;
    private final String apiKey;
    private final String model;
    private final String format;
    private final String sampleRate;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final RestClient restClient = RestClient.create();

    public QwenAsrTool(String baseUrl, String apiKey, String model, String format, String sampleRate) {
        this.baseUrl = baseUrl;
        this.apiKey = apiKey;
        this.model = model;
        this.format = format;
        this.sampleRate = sampleRate;
    }

    /**
     * 转写音频，返回完整文本（各句拼接）
     */
    public String transcribe(String audioUrl) {
        StringBuilder sb = new StringBuilder();
        for (AsrSegment segment : transcribeSegments(audioUrl)) {
            sb.append(segment.getText());
        }
        return sb.toString();
    }

    /**
     * 转写音频，返回句级分段（含毫秒时间戳）；失败时抛出异常，由调用方决定降级方式
     */
    public List<AsrSegment> transcribeSegments(String audioUrl) {
        try {
            String body = restClient.post()
                    .uri(baseUrl + "/api/v1/services/aigc/multimodal-generation/generation")
                    .header("Authorization", "Bearer " + apiKey)
                    // 关闭 SSE，同步拿完整结果
                    .header("X-DashScope-SSE", "disable")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(buildPayload(audioUrl))
                    .retrieve()
                    .body(String.class);

            JsonNode root = objectMapper.readTree(body);
            if (root.hasNonNull("code") && root.path("code").asInt(0) != 0) {
                throw new IllegalStateException("转写接口返回错误：" + root.path("code").asText()
                        + " " + root.path("message").asText(""));
            }

            List<AsrSegment> segments = new ArrayList<>();
            // 句级数组形态
            JsonNode sentences = root.path("sentences");
            if (sentences.isArray()) {
                for (JsonNode s : sentences) {
                    String text = s.path("text").asText("");
                    if (!text.isBlank()) {
                        segments.add(new AsrSegment(s.path("begin_time").asInt(0), s.path("end_time").asInt(0), text));
                    }
                }
            }
            // 单句形态（实测主形态）：sentence.begin_time / end_time / text
            if (segments.isEmpty() && root.path("sentence").isObject()) {
                JsonNode s = root.path("sentence");
                String text = s.path("text").asText("");
                if (!text.isBlank()) {
                    segments.add(new AsrSegment(s.path("begin_time").asInt(0), s.path("end_time").asInt(0), text));
                }
            }
            // 兼容标准多模态结构（output.choices[0].message.content[0].text）与顶层 text
            if (segments.isEmpty()) {
                String text = root.path("output").path("choices").path(0).path("message")
                        .path("content").path(0).path("text").asText("");
                if (text.isBlank()) {
                    text = root.path("text").asText("");
                }
                if (!text.isBlank()) {
                    // 端点未返回句级结构（当前部署实测为纯文本形态）；打印响应片段供后续切换真句级解析留依据
                    log.warn("Qwen ASR 未返回句级结构，走全文兜底, url={}, 响应片段={}", audioUrl,
                            body.substring(0, Math.min(500, body.length())));
                    segments.add(new AsrSegment(0, 0, text));
                }
            }
            if (segments.isEmpty()) {
                throw new IllegalStateException("未识别出音频中的语音内容");
            }
            log.info("Qwen ASR 转写完成, url={}, 句数={}", audioUrl, segments.size());
            return segments;
        } catch (IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            log.error("Qwen ASR 转写失败, url={}", audioUrl, e);
            throw new IllegalStateException("语音转写失败：" + e.getMessage(), e);
        }
    }

    /**
     * 兜底整块文本切分为句级分段（公开静态便于单测）：
     * 按句末标点（。！？；，保留标点）切分，按各句字符占比在 [startMs, endMs] 毫秒区间内线性分摊起止时间；
     * 单句或空文本原样返回。兜底整块无真实句级时间戳，估算粒度（秒级）远优于分片边界（280s）
     */
    public static List<AsrSegment> splitFallbackSegment(AsrSegment giant, long startMs, long endMs) {
        List<AsrSegment> result = new ArrayList<>();
        String text = giant.getText();
        if (text == null || text.isBlank()) {
            return result;
        }
        List<String> sentences = splitSentences(text);
        if (sentences.size() <= 1) {
            result.add(giant);
            return result;
        }
        int totalChars = 0;
        for (String sentence : sentences) {
            totalChars += sentence.length();
        }
        long span = Math.max(0L, endMs - startMs);
        long cursor = startMs;
        for (String sentence : sentences) {
            long sentenceMs = totalChars == 0 ? 0 : Math.round((double) span * sentence.length() / totalChars);
            long begin = cursor;
            long end = begin + sentenceMs;
            result.add(new AsrSegment((int) begin, (int) end, sentence));
            cursor = end;
        }
        // 末句对齐区间终点（吸收舍入误差遗留尾巴）
        if (!result.isEmpty()) {
            AsrSegment last = result.get(result.size() - 1);
            result.set(result.size() - 1, new AsrSegment(last.getBeginMs(), (int) endMs, last.getText()));
        }
        return result;
    }

    /** 按句末标点切分文本（保留标点；无句末标点的尾段整段保留） */
    static List<String> splitSentences(String text) {
        List<String> sentences = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        for (int i = 0; i < text.length(); i++) {
            char ch = text.charAt(i);
            current.append(ch);
            if (ch == '。' || ch == '！' || ch == '？' || ch == '；') {
                sentences.add(current.toString());
                current.setLength(0);
            }
        }
        if (current.length() > 0) {
            sentences.add(current.toString());
        }
        return sentences;
    }

    /**
     * 构造 DashScope 多模态生成请求体
     */
    private String buildPayload(String audioUrl) throws Exception {
        ObjectNode audio = objectMapper.createObjectNode();
        audio.put("type", "input_audio");
        audio.putObject("input_audio").put("data", audioUrl);

        ArrayNode content = objectMapper.createArrayNode();
        content.add(audio);

        ObjectNode message = objectMapper.createObjectNode();
        message.put("role", "user");
        message.set("content", content);

        ArrayNode messages = objectMapper.createArrayNode();
        messages.add(message);

        ObjectNode input = objectMapper.createObjectNode();
        input.set("messages", messages);

        ObjectNode parameters = objectMapper.createObjectNode();
        parameters.put("format", format);
        parameters.put("sample_rate", sampleRate);

        ObjectNode root = objectMapper.createObjectNode();
        root.put("model", model);
        root.set("input", input);
        root.set("parameters", parameters);

        return objectMapper.writeValueAsString(root);
    }
}
