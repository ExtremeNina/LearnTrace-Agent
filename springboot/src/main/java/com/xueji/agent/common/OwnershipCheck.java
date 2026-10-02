package com.xueji.agent.common;

import com.xueji.agent.exception.BusinessException;

/**
 * 归属校验收口：原本散落在会话 / 题目 / 网课 / 笔记 / Agent 对话中的同形校验
 * （不存在 / 已软删 / 非本人 → 404）统一到这里，杜绝各处写法漂移。
 */
public final class OwnershipCheck {

    private OwnershipCheck() {
    }

    /**
     * 校验实体归属：不存在、已软删或非本人时抛 404，否则原样返回实体
     *
     * @param entity          按主键查出的实体，可为 null
     * @param userId          当前登录用户 ID
     * @param notFoundMessage 404 提示文案（如"会话不存在"）
     */
    public static <T extends UserOwned> T requireOwned(T entity, Long userId, String notFoundMessage) {
        if (entity == null
                || Integer.valueOf(1).equals(entity.getDeleted())
                || entity.getUserId() == null
                || !entity.getUserId().equals(userId)) {
            throw new BusinessException(404, notFoundMessage);
        }
        return entity;
    }
}
