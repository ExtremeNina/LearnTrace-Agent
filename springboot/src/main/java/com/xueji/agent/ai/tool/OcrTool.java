package com.xueji.agent.ai.tool;

import com.aliyun.ocr_api20210707.Client;
import com.aliyun.ocr_api20210707.models.RecognizeAllTextRequest;
import com.aliyun.ocr_api20210707.models.RecognizeAllTextResponse;
import com.aliyun.teaopenapi.models.Config;
import lombok.extern.slf4j.Slf4j;

/**
 * OCR 识别端（PRD §3.3 双模型分工的识别端，阿里云读光 OCR）。
 * 由业务代码在 LLM 推理前直接调用（OCR 前置流水线），模型不再经 AI Tool 自行调用；
 * 识别结果再交由 OcrTextFormatter 整理后进入推理。
 * Bean 装配在 SpringAIConfig 中完成（构造参数来自 aliyun.ocr.* 配置）。
 */
@Slf4j
public class OcrTool {

    private final Client client;

    public OcrTool(String accessKeyId, String accessKeySecret, String endpoint) {
        try {
            Config config = new Config()
                    .setAccessKeyId(accessKeyId)
                    .setAccessKeySecret(accessKeySecret)
                    .setEndpoint(endpoint);
            this.client = new Client(config);
        } catch (Exception e) {
            throw new IllegalStateException("OCR 客户端初始化失败", e);
        }
    }

    /**
     * 识别图片文字，返回按版面顺序排列的原始文本；失败或无文字时抛出异常，由调用方决定降级方式
     */
    public String recognizeText(String imageUrl) {
        try {
            // OCR 统一识别接口，type=General 为通用文字识别（基础版）
            RecognizeAllTextRequest request = new RecognizeAllTextRequest()
                    .setType("General")
                    .setUrl(imageUrl);
            RecognizeAllTextResponse response = client.recognizeAllText(request);
            String text = response.getBody().getData().getContent();
            if (text == null || text.isBlank()) {
                throw new IllegalStateException("未识别出图片中的文字");
            }
            log.info("OCR 识别完成, url={}, 字数={}", imageUrl, text.length());
            return text;
        } catch (Exception e) {
            log.error("OCR 识别失败, url={}", imageUrl, e);
            throw new IllegalStateException("OCR 识别失败：" + e.getMessage(), e);
        }
    }
}
