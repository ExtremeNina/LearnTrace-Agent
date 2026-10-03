package com.xueji.agent.service;

/**
 * 复习调度算法（SM-2 简化版，三档评分）：
 * 每张卡只维护 间隔天数 / 容易度 / 复习次数 / 生疏次数 四个数字，
 * 每次评分后计算新状态。纯函数、无依赖，便于单元测试；将来升级 FSRS 只换本类实现。
 *
 * 规则：生疏 → 间隔重置 1 天、容易度 -0.2（下限 1.30）；
 *       模糊 → 间隔 ×1.2；
 *       熟练 → 间隔 ×容易度；
 *       首次复习（间隔 0）按 1 天起算；间隔封顶 180 天。
 */
public final class ReviewScheduler {

    /** 评分：生疏 */
    public static final int GRADE_AGAIN = 0;
    /** 评分：模糊 */
    public static final int GRADE_HARD = 1;
    /** 评分：熟练 */
    public static final int GRADE_GOOD = 2;

    public static final double EASE_DEFAULT = 2.50;
    public static final double EASE_MIN = 1.30;
    public static final double EASE_MAX = 3.00;
    /** 间隔封顶（天）：防止一张卡半年不再出现 */
    public static final int INTERVAL_MAX = 180;
    /** 首次复习的起算间隔（天） */
    public static final int INTERVAL_FIRST = 1;

    /** 调度后的卡片状态 */
    public record State(int intervalDays, double ease, int reps, int lapses) {
    }

    private ReviewScheduler() {
    }

    /**
     * 依据评分计算复习后的新状态
     *
     * @param grade        评分：GRADE_AGAIN / GRADE_HARD / GRADE_GOOD
     * @param intervalDays 当前间隔（天，0 表示尚未复习过）
     * @param ease         当前容易度
     * @param reps         已复习次数
     * @param lapses       已生疏次数
     */
    public static State next(int grade, int intervalDays, double ease, int reps, int lapses) {
        int base = Math.max(intervalDays, INTERVAL_FIRST);
        return switch (grade) {
            case GRADE_AGAIN -> new State(INTERVAL_FIRST, Math.max(EASE_MIN, ease - 0.2), reps + 1, lapses + 1);
            case GRADE_HARD -> new State(cap((int) Math.round(base * 1.2)), ease, reps + 1, lapses);
            case GRADE_GOOD -> new State(cap((int) Math.round(base * ease)), ease, reps + 1, lapses);
            default -> throw new IllegalArgumentException("非法评分: " + grade);
        };
    }

    private static int cap(int intervalDays) {
        return Math.min(Math.max(intervalDays, INTERVAL_FIRST), INTERVAL_MAX);
    }
}
