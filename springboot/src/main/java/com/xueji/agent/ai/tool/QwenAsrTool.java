package com.xueji.agent.ai.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;

/**
 * 语音转写工具（PRD §3.2 网课转写）：阿里云百炼 Qwen-Audio-3.1-ASR-Flash，
 * DashScope 多模态生成端点，传入音频 URL 返回转写文本（网课流水线的音频通道）。
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
     * 转写音频，返回识别文本；失败时抛出异常，由调用方决定降级方式
     */
    public String transcribe(String audioUrl) {
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
            if (root.hasNonNull("code")) {
                throw new IllegalStateException("转写接口返回错误：" + root.path("code").asText()
                        + " " + root.path("message").asText(""));
            }
            // output.choices[0].message.content[0].text
            String text = root.path("output")
                    .path("choices").path(0)
                    .path("message").path("content")
                    .path(0).path("text").asText("");
            if (text.isBlank()) {
                throw new IllegalStateException("未识别出音频中的语音内容");
            }
            log.info("Qwen ASR 转写完成, url={}, 字数={}", audioUrl, text.length());
            return text;
        } catch (IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            log.error("Qwen ASR 转写失败, url={}", audioUrl, e);
            throw new IllegalStateException("语音转写失败：" + e.getMessage(), e);
        }
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
