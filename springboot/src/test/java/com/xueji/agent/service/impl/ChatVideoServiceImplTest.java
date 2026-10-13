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

    @Test
    void courseTitleFromVideoNameShouldCleanAndFallback() {
        // 权威文件名：去扩展名、清洗非法字符
        assertThat(ChatVideoServiceImpl.courseTitleFromVideoName("线性代数第5讲.mp4")).isEqualTo("线性代数第5讲");
        // B 站占位名：回退调用方的临时文件名规则（返回 null）
        assertThat(ChatVideoServiceImpl.courseTitleFromVideoName("B 站视频链接")).isNull();
        // 空 / 空白：回退
        assertThat(ChatVideoServiceImpl.courseTitleFromVideoName(null)).isNull();
        assertThat(ChatVideoServiceImpl.courseTitleFromVideoName("  ")).isNull();
    }
}
