package com.xueji.agent.service;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 复习调度算法（SM-2 简化版）：三档评分的间隔推进、容易度边界、间隔封顶
 */
class ReviewSchedulerTest {

    @Test
    void againShouldResetIntervalToTomorrowAndLowerEase() {
        ReviewScheduler.State state = ReviewScheduler.next(ReviewScheduler.GRADE_AGAIN, 25, 2.5, 6, 1);

        assertEquals(1, state.intervalDays());
        assertEquals(2.3, state.ease(), 0.001);
        assertEquals(7, state.reps());
        assertEquals(2, state.lapses());
    }

    @Test
    void hardShouldGrowIntervalSlowly() {
        ReviewScheduler.State state = ReviewScheduler.next(ReviewScheduler.GRADE_HARD, 10, 2.5, 3, 0);

        assertEquals(12, state.intervalDays());
        assertEquals(2.5, state.ease(), 0.001);
        assertEquals(4, state.reps());
        assertEquals(0, state.lapses());
    }

    @Test
    void goodShouldGrowIntervalByEase() {
        ReviewScheduler.State state = ReviewScheduler.next(ReviewScheduler.GRADE_GOOD, 10, 2.5, 3, 0);

        assertEquals(25, state.intervalDays());
    }

    @Test
    void firstReviewShouldStartFromOneDay() {
        ReviewScheduler.State good = ReviewScheduler.next(ReviewScheduler.GRADE_GOOD, 0, 2.5, 0, 0);
        ReviewScheduler.State hard = ReviewScheduler.next(ReviewScheduler.GRADE_HARD, 0, 2.5, 0, 0);
        ReviewScheduler.State again = ReviewScheduler.next(ReviewScheduler.GRADE_AGAIN, 0, 2.5, 0, 0);

        assertEquals(3, good.intervalDays());
        assertEquals(1, hard.intervalDays());
        assertEquals(1, again.intervalDays());
    }

    @Test
    void easeShouldStayWithinBounds() {
        // 连续生疏：ease 触底 1.30
        ReviewScheduler.State state = ReviewScheduler.next(ReviewScheduler.GRADE_AGAIN, 1, 1.35, 5, 4);
        assertEquals(1.30, state.ease(), 0.001);

        // ease 已到上限时保持 3.00（熟练不改 ease）
        ReviewScheduler.State maxed = ReviewScheduler.next(ReviewScheduler.GRADE_GOOD, 10, 3.0, 5, 0);
        assertEquals(3.0, maxed.ease(), 0.001);
        assertTrue(maxed.intervalDays() <= ReviewScheduler.INTERVAL_MAX);
    }

    @Test
    void intervalShouldCapAtMax() {
        ReviewScheduler.State state = ReviewScheduler.next(ReviewScheduler.GRADE_GOOD, 150, 2.5, 8, 0);

        assertEquals(ReviewScheduler.INTERVAL_MAX, state.intervalDays());
    }

    @Test
    void illegalGradeShouldThrow() {
        assertThrows(IllegalArgumentException.class, () -> ReviewScheduler.next(9, 1, 2.5, 1, 0));
    }
}
