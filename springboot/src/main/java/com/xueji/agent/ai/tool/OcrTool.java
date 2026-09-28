package com.xueji.agent.ai.tool;

/**
 * OCR 识别抽象（PRD §3.3 识别端）：前置流水线在 LLM 推理前调用，
 * 实现类只负责"图片 URL → 原始识别文本"，格式整理由 OcrTextFormatter 负责。
 */
public interface OcrTool {

    /**
     * 识别图片文字，返回按版面顺序排列的原始文本；失败时抛出异常，由调用方决定降级方式
     */
    String recognizeText(String imageUrl);
}
