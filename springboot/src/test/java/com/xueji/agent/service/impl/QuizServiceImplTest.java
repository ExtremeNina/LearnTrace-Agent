package com.xueji.agent.service.impl;

import com.xueji.agent.domain.vo.QuizPickVO;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 练习抽题纯函数（QuizServiceImpl.shufflePick）单测：数量封顶、池子不足返全部、非法数量返空。
 * 随机源固定种子，保证断言可复现。
 */
class QuizServiceImplTest {

    @Test
    void shufflePickShouldReturnRequestedCountWithoutDuplicates() {
        List<QuizPickVO> pool = new ArrayList<>();
        for (int i = 0; i < 50; i++) {
            pool.add(new QuizPickVO().setCardType("question").setRefId((long) i));
        }
        List<QuizPickVO> picked = QuizServiceImpl.shufflePick(pool, 10, new Random(42));
        assertEquals(10, picked.size());
        for (int i = 0; i < picked.size(); i++) {
            for (int j = i + 1; j < picked.size(); j++) {
                assertTrue(picked.get(i).getRefId() != picked.get(j).getRefId(),
                        "同一题不应被重复抽出");
            }
        }
    }

    @Test
    void shufflePickShouldReturnAllWhenPoolSmallerThanCount() {
        List<QuizPickVO> pool = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            pool.add(new QuizPickVO().setCardType("similar").setRefId((long) i));
        }
        List<QuizPickVO> picked = QuizServiceImpl.shufflePick(pool, 10, new Random(42));
        assertEquals(3, picked.size());
    }

    @Test
    void shufflePickShouldReturnEmptyForNonPositiveCount() {
        List<QuizPickVO> pool = new ArrayList<>();
        pool.add(new QuizPickVO().setCardType("question").setRefId(1L));
        assertEquals(0, QuizServiceImpl.shufflePick(pool, 0, new Random(42)).size());
    }
}
