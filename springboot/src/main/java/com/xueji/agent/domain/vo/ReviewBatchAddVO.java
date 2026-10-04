package com.xueji.agent.domain.vo;

import lombok.Data;
import lombok.experimental.Accessors;

/**
 * 批量加入复习结果（已在队列或来源不存在的计为跳过）
 */
@Data
@Accessors(chain = true)
public class ReviewBatchAddVO {

    /** 成功加入数量 */
    private int addedCount;

    /** 跳过数量（已在队列 / 无效项 / 来源不存在） */
    private int skippedCount;
}
