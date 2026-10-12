package com.xueji.agent.common;

import java.util.List;

/**
 * 学科白名单（与前端 web/src/constants/subjects.ts 的 SUBJECTS 同源）：
 * 网课/题目入库、徽标展示与列表筛选共用一表，保证入库值与筛选值一致。
 * LLM 推断（意图建课工具参数 / 兜底回填）结果不在表内时收敛为「其他」。
 */
public final class Subjects {

    public static final List<String> ALL = List.of(
            "数学", "语文", "英语", "物理", "化学", "生物", "历史", "地理", "政治", "计算机", "其他");

    public static final String OTHER = "其他";

    private Subjects() {
    }

    /** 白名单收敛：空白返回 null（不标注）；不在表内的映射为「其他」 */
    public static String clamp(String subject) {
        if (subject == null || subject.isBlank()) {
            return null;
        }
        String trimmed = subject.trim();
        return ALL.contains(trimmed) ? trimmed : OTHER;
    }
}
