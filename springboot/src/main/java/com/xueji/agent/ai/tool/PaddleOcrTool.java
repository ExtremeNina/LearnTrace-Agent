package com.xueji.agent.ai.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;

import java.util.ArrayList;
import java.util.List;

/**
 * 百度 PaddleOCR 实现（AI Studio 星河社区 PaddleOCR-VL 云端服务，异步任务流）：
 * 提交识别 job → 轮询状态 → 拉取 NDJSON 结果 → 拼接 Markdown 版面文本。
 * 输出为结构化 Markdown（多栏 / 表格 / 公式还原较好），适合试卷与复杂版面题目。
 * Bean 装配在 SpringAIConfig 中完成（构造参数来自 paddle-ocr.* 配置）。
 */
@Slf4j
public class PaddleOcrTool implements OcrTool {

    private static final long POLL_INTERVAL_MS = 3000;

    /** 轮询上限（秒）：可配置；超时抛出异常，由调用方降级提示，不再让请求侧长时间无反馈 */
    private final long timeoutSeconds;

    private final String apiBase;
    private final String token;
    private final String model;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final RestClient restClient = RestClient.create();

    public PaddleOcrTool(String apiBase, String token, String model, long pollTimeoutSeconds) {
        this.apiBase = apiBase;
        this.token = token;
        this.model = model;
        this.timeoutSeconds = pollTimeoutSeconds;
    }

    @Override
    public String recognizeText(String imageUrl) {
        try {
            String jobId = submitJob(imageUrl);
            String jsonUrl = pollResult(jobId);
            return fetchMarkdown(jsonUrl);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("PaddleOCR 识别被中断", e);
        } catch (IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            log.error("PaddleOCR 识别失败, url={}", imageUrl, e);
            throw new IllegalStateException("PaddleOCR 识别失败：" + e.getMessage(), e);
        }
    }

    /**
     * 提交识别任务，返回 jobId
     */
    private String submitJob(String imageUrl) throws Exception {
        ObjectNode optional = objectMapper.createObjectNode();
        optional.put("useDocOrientationClassify", false);
        optional.put("useDocUnwarping", false);
        optional.put("useChartRecognition", false);

        ObjectNode payload = objectMapper.createObjectNode();
        payload.put("fileUrl", imageUrl);
        payload.put("model", model);
        payload.set("optionalPayload", optional);

        String body = restClient.post()
                .uri(apiBase + "/jobs")
                .header("Authorization", "bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .body(objectMapper.writeValueAsString(payload))
                .retrieve()
                .body(String.class);

        JsonNode job = objectMapper.readTree(body);
        String jobId = job.path("data").path("jobId").asText("");
        if (jobId.isBlank()) {
            throw new IllegalStateException("任务提交失败：" + truncate(body));
        }
        log.info("PaddleOCR 任务已提交, jobId={}", jobId);
        return jobId;
    }

    /**
     * 轮询任务状态直至完成，返回结果 NDJSON 的下载地址
     */
    private String pollResult(String jobId) throws Exception {
        long deadline = System.currentTimeMillis() + timeoutSeconds * 1000;
        while (System.currentTimeMillis() < deadline) {
            Thread.sleep(POLL_INTERVAL_MS);
            String body = restClient.get()
                    .uri(apiBase + "/jobs/" + jobId)
                    .header("Authorization", "bearer " + token)
                    .retrieve()
                    .body(String.class);
            JsonNode data = objectMapper.readTree(body).path("data");
            String state = data.path("state").asText("");
            if ("done".equals(state)) {
                return data.path("resultUrl").path("jsonUrl").asText("");
            }
            if ("failed".equals(state)) {
                throw new IllegalStateException("任务失败：" + data.path("errorMsg").asText("未知原因"));
            }
            // pending / running：继续等待
        }
        throw new IllegalStateException("识别超时（" + timeoutSeconds + "s），请稍后重试");
    }

    /**
     * 拉取 NDJSON 结果，拼接每页的 Markdown 文本。
     * 注意：jsonUrl 是 BOS 签名 URL，签名对 URL 字节敏感——用 java.net.URL 裸请求，
     * 避免 RestClient 的 URI 模板编码 / 头部处理破坏签名
     */
    private String fetchMarkdown(String jsonUrl) throws Exception {
        String ndjson;
        try (var in = java.net.URI.create(jsonUrl).toURL().openStream()) {
            ndjson = new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        }
        List<String> pages = new ArrayList<>();
        for (String line : ndjson.split("\n")) {
            if (line.isBlank()) {
                continue;
            }
            JsonNode result = objectMapper.readTree(line).path("result");
            for (JsonNode page : result.path("layoutParsingResults")) {
                String text = page.path("markdown").path("text").asText("");
                if (!text.isBlank()) {
                    pages.add(text);
                }
            }
        }
        if (pages.isEmpty()) {
            throw new IllegalStateException("未识别出图片中的文字");
        }
        log.info("PaddleOCR 识别完成, 页数={}", pages.size());
        return String.join("\n\n", pages);
    }

    private String truncate(String s) {
        return s == null ? "" : s.substring(0, Math.min(200, s.length()));
    }
}
