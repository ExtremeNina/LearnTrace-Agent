package com.xueji.agent.common;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 统一响应封装的基础语义
 */
class ResultTest {

    @Test
    void okShouldReturnSuccessCode() {
        Result<Void> result = Result.ok();
        assertThat(result.getCode()).isEqualTo(200);
        assertThat(result.getMessage()).isEqualTo("success");
        assertThat(result.getData()).isNull();
    }

    @Test
    void dataShouldCarryPayload() {
        Result<String> result = Result.data("hello");
        assertThat(result.getCode()).isEqualTo(200);
        assertThat(result.getData()).isEqualTo("hello");
    }

    @Test
    void okWithMessageShouldKeepCustomMessage() {
        Result<Void> result = Result.ok("注册成功");
        assertThat(result.getCode()).isEqualTo(200);
        assertThat(result.getMessage()).isEqualTo("注册成功");
    }

    @Test
    void errorShouldDefaultTo500() {
        Result<Void> result = Result.error("出错了");
        assertThat(result.getCode()).isEqualTo(500);
        assertThat(result.getMessage()).isEqualTo("出错了");
    }

    @Test
    void errorShouldSupportCustomCode() {
        Result<Void> result = Result.error(401, "未登录");
        assertThat(result.getCode()).isEqualTo(401);
        assertThat(result.getMessage()).isEqualTo("未登录");
    }
}
