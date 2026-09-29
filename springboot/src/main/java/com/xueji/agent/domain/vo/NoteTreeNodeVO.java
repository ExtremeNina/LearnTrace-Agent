package com.xueji.agent.domain.vo;

import lombok.Getter;
import lombok.Setter;

import java.util.ArrayList;
import java.util.List;

/**
 * 笔记分层树节点（分组 / 笔记叶子）
 */
@Getter
@Setter
public class NoteTreeNodeVO {

    private Long id;

    private String title;

    /** group / note */
    private String type;

    /** 笔记节点：来源（AI 生成 / 手动创建） */
    private String source;

    private String updatedAt;

    private List<NoteTreeNodeVO> children = new ArrayList<>();
}
