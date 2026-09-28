package com.xueji.agent.common;

/**
 * RabbitMQ 队列 / 交换机名称统一收口
 */
public final class MqKeys {

    private MqKeys() {
    }

    /** 网课处理交换机（Direct） */
    public static final String COURSE_EXCHANGE = "xj.course";

    /** 网课处理队列：上传完成后触发流水线 */
    public static final String COURSE_PROCESS_QUEUE = "xj.course.process";

    /** 网课处理路由键 */
    public static final String COURSE_PROCESS_ROUTING = "process";
}
