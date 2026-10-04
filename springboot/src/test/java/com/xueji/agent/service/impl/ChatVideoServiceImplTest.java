package com.xueji.agent.service.impl;

import com.xueji.agent.exception.BusinessException;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 对话视频上传时长校验单测（B11）：30 分钟上限与引导文案（去视频管理上传）
 */
class ChatVideoServiceImplTest {

    private final ChatVideoServiceImpl service = new ChatVideoServiceImpl();

    @Test
    void durationAtLimitShouldPass() {
        assertThatCode(() -> service.assertDurationAllowed(30 * 60)).doesNotThrowAnyException();
    }

    @Test
    void durationOverLimitShouldRejectWithGuideMessage() {
        assertThatThrownBy(() -> service.assertDurationAllowed(30 * 60 + 1))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("视频时长超过 30 分钟")
                .hasMessageContaining("视频管理");
    }
}
