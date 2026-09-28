package com.xueji.agent.ai.tool;

import lombok.Getter;

/**
 * ASR 句级转写结果（毫秒时间戳）
 */
@Getter
public class AsrSegment {

    private final int beginMs;
    private final int endMs;
    private final String text;

    public AsrSegment(int beginMs, int endMs, String text) {
        this.beginMs = beginMs;
        this.endMs = endMs;
        this.text = text;
    }
}
