package com.xueji.agent.domain.vo;

import lombok.AllArgsConstructor;
import lombok.Getter;

import java.util.List;

/**
 * 通用分页结果（PRD 初期：够用即可，手写 limit/offset 分页）
 */
@Getter
@AllArgsConstructor
public class PageVO<T> {

    /** 当前页数据 */
    private final List<T> list;

    /** 总条数 */
    private final long total;

    /** 当前页码（从 1 开始） */
    private final long page;

    /** 每页条数 */
    private final long size;

    /** 总页数 */
    public long getPages() {
        return size == 0 ? 0 : (total + size - 1) / size;
    }
}
