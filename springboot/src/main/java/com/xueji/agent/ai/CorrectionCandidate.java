package com.xueji.agent.ai;

import lombok.Getter;

/**
 * 转写修正候选（B26 阶段 1 修正管线）：
 * LLM 审校节点产出；佐证确认（帧 OCR 交叉 / 领域词典 / 重解码）后才允许自动应用
 */
@Getter
public class CorrectionCandidate {

    /** 候选所属转写分段的 sort 序号 */
    private final int sort;

    /** 疑似错误的原文（词或短语） */
    private final String original;

    /** 建议修正写法 */
    private final String suggestion;

    /** 简短理由 */
    private final String reason;

    public CorrectionCandidate(int sort, String original, String suggestion, String reason) {
        this.sort = sort;
        this.original = original;
        this.suggestion = suggestion;
        this.reason = reason;
    }
}
