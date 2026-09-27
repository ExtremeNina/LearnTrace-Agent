package com.xueji.agent.exception;

import lombok.Getter;

/**
 * 文件上传异常
 */
@Getter
public class UploadException extends RuntimeException {

    private final int code;

    public UploadException(String message, int code) {
        super(message);
        this.code = code;
    }

    public UploadException(String message) {
        this(message, 500);
    }

    public UploadException() {
        this("上传失败，请稍后重试", 500);
    }
}
