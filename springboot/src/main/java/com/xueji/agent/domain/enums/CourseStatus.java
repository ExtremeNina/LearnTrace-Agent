package com.xueji.agent.domain.enums;

/**
 * 网课处理状态收口：course.status 与 course_frame.ocr_status 共用词表，
 * 值落库为同名字符串（前端按字符串展示），禁止在业务代码中散落硬编码字面量。
 */
public final class CourseStatus {

    private CourseStatus() {
    }

    /** 待处理（上传完成等待流水线 / 重试已重新入队） */
    public static final String PENDING = "PENDING";

    /** 流水线处理中 */
    public static final String PROCESSING = "PROCESSING";

    /** 处理成功 */
    public static final String SUCCESS = "SUCCESS";

    /** 处理失败 */
    public static final String FAILED = "FAILED";
}
