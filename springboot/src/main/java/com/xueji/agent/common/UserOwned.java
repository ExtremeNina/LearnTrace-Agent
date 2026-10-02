package com.xueji.agent.common;

/**
 * 归属校验统一契约：带 userId 的实体实现本接口，配合 OwnershipCheck 做同形校验。
 * 无软删位的实体（如会话）不必实现 getDeleted，缺省视为未删除。
 */
public interface UserOwned {

    /** 归属用户 ID */
    Long getUserId();

    /** 软删标记（0/1）；无该字段的实体返回 null，视为未删除 */
    default Integer getDeleted() {
        return null;
    }
}
