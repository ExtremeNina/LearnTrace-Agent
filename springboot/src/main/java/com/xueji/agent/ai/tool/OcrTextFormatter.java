package com.xueji.agent.ai.tool;

import org.springframework.stereotype.Component;

/**
 * OCR 文本格式化工具：在 OCR 识别与 LLM 推理之间做确定性文本整理（不调 LLM）。
 * 修复读光 OCR 常见的版面粘连问题：标题与正文挤在一行（"##解题步骤###第（1）问"）、
 * 标题号后缺空格、行尾空白与冗余空行，让推理模型拿到结构清晰的题目文本。
 */
@Component
public class OcrTextFormatter {

    /**
     * 整理 OCR 原始文本，返回规范化的 Markdown 文本；空输入返回空串
     */
    public String format(String raw) {
        if (raw == null || raw.isBlank()) {
            return "";
        }
        String text = raw.replace("\r\n", "\n").replace("\u00A0", " ").trim();

        // 1. 标题与前面正文粘连时断行并补空格："…步骤###第（1）问" → "…步骤\n### 第（1）问"
        //    仅当 # 串前面是非空白字符且后面紧跟中文/字母时才视为标题，避免误伤 "#1" 之类的编号
        text = text.replaceAll("(?<![\\s#])(#{1,6})(?=[一-龥A-Za-z])", "\n$1 ");

        // 2. 行首标题缺空格："##解题" → "## 解题"
        text = text.replaceAll("(?m)^(#{1,6})(?=[一-龥A-Za-z])", "$1 ");

        // 3. 收敛行尾空白与 3 个以上连续换行
        text = text.replaceAll("(?m)[ \t]+$", "");
        text = text.replaceAll("\n{3,}", "\n\n");

        return text.trim();
    }
}
