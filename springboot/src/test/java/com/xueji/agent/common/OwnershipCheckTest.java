package com.xueji.agent.common;

import com.xueji.agent.domain.entity.Conversation;
import com.xueji.agent.domain.entity.Course;
import com.xueji.agent.exception.BusinessException;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 归属校验收口：不存在 / 已软删 / 非本人 → 404；无软删位实体（会话）缺省视为未删除
 */
class OwnershipCheckTest {

    private static final Long USER_ID = 5L;

    @Test
    void nullEntityShouldThrow404() {
        assertThatThrownBy(() -> OwnershipCheck.requireOwned(null, USER_ID, "会话不存在"))
                .isInstanceOf(BusinessException.class)
                .hasMessage("会话不存在")
                .extracting(e -> ((BusinessException) e).getCode())
                .isEqualTo(404);
    }

    @Test
    void foreignEntityShouldThrow404() {
        Conversation foreign = new Conversation().setId(1L).setUserId(999L);

        assertThatThrownBy(() -> OwnershipCheck.requireOwned(foreign, USER_ID, "会话不存在"))
                .isInstanceOf(BusinessException.class)
                .hasMessage("会话不存在");
    }

    @Test
    void nullOwnerUserIdShouldThrow404() {
        Conversation noOwner = new Conversation().setId(1L).setUserId(null);

        assertThatThrownBy(() -> OwnershipCheck.requireOwned(noOwner, USER_ID, "会话不存在"))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    void softDeletedEntityShouldThrow404() {
        Course deleted = new Course().setId(2L).setUserId(USER_ID).setDeleted(1);

        assertThatThrownBy(() -> OwnershipCheck.requireOwned(deleted, USER_ID, "网课不存在"))
                .isInstanceOf(BusinessException.class)
                .hasMessage("网课不存在");
    }

    @Test
    void ownedAliveEntityShouldPass() {
        Course mine = new Course().setId(2L).setUserId(USER_ID).setDeleted(0).setTitle("我的网课");

        Course result = OwnershipCheck.requireOwned(mine, USER_ID, "网课不存在");

        assertThat(result).isSameAs(mine);
    }

    @Test
    void entityWithoutSoftDeleteFieldShouldPass() {
        // 会话无 deleted 字段，接口缺省实现返回 null，视为未删除
        Conversation mine = new Conversation().setId(1L).setUserId(USER_ID);

        Conversation result = OwnershipCheck.requireOwned(mine, USER_ID, "会话不存在");

        assertThat(result).isSameAs(mine);
    }
}
