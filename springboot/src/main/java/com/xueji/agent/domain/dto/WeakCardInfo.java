package com.xueji.agent.domain.dto;

import lombok.Data;
import lombok.experimental.Accessors;

/** Agent 工具 get_learning_status 的单张薄弱卡信息（供 LLM 归纳薄弱主题） */
@Data
@Accessors(chain = true)
public class WeakCardInfo {

    /** question / similar / note */
    private String cardType;

    private Long refId;

    /** 题干 / 标题 */
    private String front;

    /** 错因 / 解析（可选） */
    private String analysis;

    /** 学科（题目类才有） */
    private String subject;
}
