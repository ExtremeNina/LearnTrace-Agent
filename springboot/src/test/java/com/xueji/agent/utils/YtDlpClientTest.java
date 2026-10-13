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

    @Test
    void cookiesArgsShouldExpandBrowsersAndSkipBlank() {
        // 逗号分隔多浏览器逐个展开；空白片段与空白配置跳过
        assertThat(YtDlpClient.cookiesArgs("edge,chrome"))
                .containsExactly("--cookies-from-browser", "edge", "--cookies-from-browser", "chrome");
        assertThat(YtDlpClient.cookiesArgs(" edge , chrome "))
                .containsExactly("--cookies-from-browser", "edge", "--cookies-from-browser", "chrome");
        assertThat(YtDlpClient.cookiesArgs("edge,,chrome"))
                .containsExactly("--cookies-from-browser", "edge", "--cookies-from-browser", "chrome");
        // 空 / null 配置：游客模式，零参数（与历史行为一致）
        assertThat(YtDlpClient.cookiesArgs("")).isEmpty();
        assertThat(YtDlpClient.cookiesArgs(" , ")).isEmpty();
        assertThat(YtDlpClient.cookiesArgs(null)).isEmpty();
    }

    @Test
    void buildCommandArgsShouldCarryTimeoutCookiesAndExtras() {
        // 固定前缀：程序路径 + --no-playlist + 防卡死 socket 超时
        assertThat(YtDlpClient.buildCommandArgs("yt-dlp.exe", "", "--skip-download", "--print", "t"))
                .containsExactly("yt-dlp.exe", "--no-playlist", "--socket-timeout", "20",
                        "--skip-download", "--print", "t");
        // cookies 配置注入在固定前缀与额外参数之间
        assertThat(YtDlpClient.buildCommandArgs("yt-dlp.exe", "edge", "-f", "bv"))
                .containsExactly("yt-dlp.exe", "--no-playlist", "--socket-timeout", "20",
                        "--cookies-from-browser", "edge", "-f", "bv");
    }

    @Test
    void cookieDatabaseErrorsShouldMapToCloseBrowserHint() {
        // cookie 库被锁（浏览器未完全关闭）→ 优先于通用 "cookies"→登录文案，提示关闭浏览器
        assertThat(YtDlpClient.classifyError("ERROR: could not copy Chrome cookie database: process cannot access"))
                .contains("读取浏览器 Cookie 失败");
    }
}
