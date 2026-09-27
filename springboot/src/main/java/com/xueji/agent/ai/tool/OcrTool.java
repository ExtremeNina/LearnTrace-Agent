package com.xueji.agent.ai.tool;

import com.aliyun.ocr_api20210707.Client;
import com.aliyun.ocr_api20210707.models.RecognizeAllTextRequest;
import com.aliyun.ocr_api20210707.models.RecognizeAllTextResponse;
import com.aliyun.teaopenapi.models.Config;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;

/**
 * OCR 工具：题目识别（PRD §3.3 双模型分工的识别端，阿里云读光 OCR）。
 * 注册进 ChatClient 后由模型按需调用，业务代码不直接调用。
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

    @Tool(description = "识别图片中的文字（OCR）。传入图片的公网访问 URL，返回按版面顺序排列的文字内容。用于题目拍照识别等场景")
    public String recognizeText(
            @ToolParam(description = "图片的公网访问 URL，需为 http/https 链接") String imageUrl) {
        try {
            // OCR 统一识别接口，type=General 为通用文字识别（基础版）
            RecognizeAllTextRequest request = new RecognizeAllTextRequest()
                    .setType("General")
                    .setUrl(imageUrl);
            RecognizeAllTextResponse response = client.recognizeAllText(request);
            String text = response.getBody().getData().getContent();
            if (text == null || text.isBlank()) {
                return "OCR 未能识别出图片中的文字";
            }
            log.info("OCR 识别完成, url={}, 字数={}", imageUrl, text.length());
            return text;
        } catch (Exception e) {
            log.error("OCR 识别失败, url={}", imageUrl, e);
            return "OCR 识别失败：" + e.getMessage() + "。请提示用户图片链接是否有效，或建议重新上传图片";
        }
    }
}
