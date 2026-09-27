package com.xueji.agent.common;

import lombok.Getter;

/**
 * 统一响应封装：{"code":200,"message":"success","data":{}}
 */
@Getter
public class Result<T> {

    public static final int CODE_SUCCESS = 200;
    public static final int CODE_ERROR = 500;

    private final int code;
    private final String message;
    private final T data;

    private Result(int code, String message, T data) {
        this.code = code;
        this.message = message;
        this.data = data;
    }

    public static <T> Result<T> ok() {
        return new Result<>(CODE_SUCCESS, "success", null);
    }

    public static <T> Result<T> ok(String message) {
        return new Result<>(CODE_SUCCESS, message, null);
    }

    public static <T> Result<T> data(T data) {
        return new Result<>(CODE_SUCCESS, "success", data);
    }

    public static <T> Result<T> error(String message) {
        return new Result<>(CODE_ERROR, message, null);
    }

    public static <T> Result<T> error(int code, String message) {
        return new Result<>(code, message, null);
    }
}
