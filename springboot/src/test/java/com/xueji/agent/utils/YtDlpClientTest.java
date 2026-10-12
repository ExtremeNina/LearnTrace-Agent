package com.xueji.agent.utils;

import com.xueji.agent.exception.BusinessException;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * B 站链接校验与 yt-dlp 错误分类
 */
class YtDlpClientTest {

    @Test
    void validBilibiliUrlShouldPass() {
        assertThat(YtDlpClient.validateBilibiliUrl("https://www.bilibili.com/video/BV1GJ411x7h7"))
                .isEqualTo("https://www.bilibili.com/video/BV1GJ411x7h7");
        assertThat(YtDlpClient.validateBilibiliUrl("https://bilibili.com/video/BV1GJ411x7h7?spm_id_from=1"))
                .contains("BV1GJ411x7h7");
    }

    @Test
    void nonBilibiliOrMalformedUrlShouldBeRejected() {
        assertThatThrownBy(() -> YtDlpClient.validateBilibiliUrl("https://www.youtube.com/watch?v=x"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("仅支持 B 站视频链接");
        assertThatThrownBy(() -> YtDlpClient.validateBilibiliUrl("https://bilibili.com/video/av170001"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("仅支持 B 站视频链接");
        assertThatThrownBy(() -> YtDlpClient.validateBilibiliUrl("  "))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("请提供视频链接");
    }

    @Test
    void multiPartBeyondFirstShouldBeRejected() {
        assertThatThrownBy(() -> YtDlpClient.validateBilibiliUrl("https://www.bilibili.com/video/BV1GJ411x7h7?p=3"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("多 P");
        // p=1 为首 P，放行
        assertThat(YtDlpClient.validateBilibiliUrl("https://www.bilibili.com/video/BV1GJ411x7h7?p=1"))
                .contains("BV1GJ411x7h7");
    }

    @Test
    void ytDlpErrorsShouldMapToReadableMessages() {
        assertThat(YtDlpClient.classifyError("ERROR: [bilibili] BV1xx: Video unavailable"))
                .contains("视频不存在或已被删除");
        assertThat(YtDlpClient.classifyError("ERROR: This video is only available for premium users"))
                .contains("登录或付费权限");
        assertThat(YtDlpClient.classifyError("Unsupported URL: https://example.com"))
                .contains("仅支持 B 站视频链接");
        assertThat(YtDlpClient.classifyError("some unknown network failure"))
                .contains("链接视频拉取失败");
    }
}
