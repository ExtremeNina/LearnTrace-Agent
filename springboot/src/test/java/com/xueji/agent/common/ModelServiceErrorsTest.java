package com.xueji.agent.common;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 模型服务余额不足识别单测：HTTP 402 文本命中、嵌套 cause 命中、普通异常与空值不命中
 */
class ModelServiceErrorsTest {

    @Test
    void shouldMatchBalanceErrorVariants() {
        assertThat(ModelServiceErrors.isBalanceInsufficient(new RuntimeException(
                "HTTP 402 - {\"error\":{\"message\":\"Insufficient Balance\"}}"))).isTrue();
        assertThat(ModelServiceErrors.isBalanceInsufficient(new RuntimeException("Insufficient Balance"))).isTrue();
        assertThat(ModelServiceErrors.isBalanceInsufficient(new RuntimeException("402 Payment Required"))).isTrue();
    }

    @Test
    void shouldMatchThroughNestedCause() {
        RuntimeException wrapped = new RuntimeException("调用模型服务失败",
                new RuntimeException("REST call failed",
                        new RuntimeException("HTTP 402 - Insufficient Balance")));
        assertThat(ModelServiceErrors.isBalanceInsufficient(wrapped)).isTrue();
    }

    @Test
    void shouldNotMatchOtherErrors() {
        assertThat(ModelServiceErrors.isBalanceInsufficient(new RuntimeException("HTTP 500 - Internal Error"))).isFalse();
        assertThat(ModelServiceErrors.isBalanceInsufficient(new RuntimeException("HTTP 429 - rate limited"))).isFalse();
        // 普通数字 4020 不误判
        assertThat(ModelServiceErrors.isBalanceInsufficient(new RuntimeException("offset 4020 out of range"))).isFalse();
        assertThat(ModelServiceErrors.isBalanceInsufficient(new RuntimeException("timeout"))).isFalse();
        assertThat(ModelServiceErrors.isBalanceInsufficient(null)).isFalse();
        assertThat(ModelServiceErrors.BALANCE_MESSAGE).contains("余额不足");
    }
}
