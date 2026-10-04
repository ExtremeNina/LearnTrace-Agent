package com.xueji.agent.mq;

import lombok.Data;

/**
 * 网课处理消息负载：经 Jackson2JsonMessageConverter（RabbitMQConfig）序列化为 JSON，
 * 生产者（CourseServiceImpl upload / retry）与消费者（CourseProcessConsumer）共用同一契约
 */
@Data
public class CourseProcessMessage {

    private Long courseId;

    /** 上传落地的临时文件路径；重试场景可能为空字符串，由流水线自行校验 */
    private String tempPath;
}
