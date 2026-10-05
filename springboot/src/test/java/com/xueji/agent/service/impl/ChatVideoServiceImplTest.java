package com.xueji.agent.service.impl;

import com.xueji.agent.service.ChatVideoService;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 对话视频上传常量回归（B11 分流改造）：时长不再在上传层拒绝（分流移到 TranscribeVideoTool），
 * 大小上限统一 1GB
 */
class ChatVideoServiceImplTest {

    @Test
    void sizeLimitShouldBeOneGb() {
        assertThat(ChatVideoService.MAX_CHAT_VIDEO_SIZE).isEqualTo(1024L * 1024 * 1024);
    }
}
