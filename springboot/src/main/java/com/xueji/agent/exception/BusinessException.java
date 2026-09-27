package com.xueji.agent.exception;

import lombok.Getter;

/**
 * 业务异常：message 面向用户展示，code 与前端错误码约定一致
 */
@Getter
public class BusinessException extends RuntimeException {

    private final int code;

    public BusinessException(String message) {
        super(message);
        this.code = 500;
    }

    public BusinessException(int code, String message) {
        super(message);
        this.code = code;
    }
}
