package com.xueji.agent.ai.tool;

import com.aliyun.ocr_api20210707.Client;
import com.aliyun.ocr_api20210707.models.RecognizeAllTextRequest;
import com.aliyun.ocr_api20210707.models.RecognizeAllTextResponse;
import com.aliyun.teaopenapi.models.Config;
import lombok.extern.slf4j.Slf4j;

/**
 * 阿里云读光 OCR 实现（OCR 统一识别接口，Type=General 通用文字识别）。
 * 快速、纯文本输出；复杂版面（试卷 / 表格 / 公式）建议用 PaddleOcrTool。
 * Bean 装配在 SpringAIConfig 中完成（构造参数来自 aliyun.ocr.* 配置）。
 */
@Slf4j
public class AliyunOcrTool implements OcrTool {

    private final Client client;

    public AliyunOcrTool(String accessKeyId, String accessKeySecret, String endpoint) {
        try {
            Config config = new Config()
                    .setAccessKeyId(accessKeyId)
                    .setAccessKeySecret(accessKeySecret)
                    .setEndpoint(endpoint);
            this.client = new Client(config);
        } catch (Exception e) {
            throw new IllegalStateException("阿里云 OCR 客户端初始化失败", e);
        }
    }

    @Override
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
            log.info("阿里云 OCR 识别完成, url={}, 字数={}", imageUrl, text.length());
            return text;
        } catch (Exception e) {
            log.error("阿里云 OCR 识别失败, url={}", imageUrl, e);
            throw new IllegalStateException("OCR 识别失败：" + e.getMessage(), e);
        }
    }
}
