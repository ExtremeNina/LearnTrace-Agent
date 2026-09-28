package com.xueji.agent.mq;

import com.rabbitmq.client.Channel;
import com.xueji.agent.service.impl.CoursePipelineService;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.amqp.support.AmqpHeaders;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.messaging.handler.annotation.Headers;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.Map;

/**
 * 网课处理消费者（手动 ack）：payload 为 { courseId, tempPath }。
 * 失败不重新入队（流水线内部已置 FAILED，用户可通过重试接口再次触发），避免毒消息无限重投。
 */
@Slf4j
@Component
public class CourseProcessConsumer {

    @Resource
    private CoursePipelineService coursePipelineService;

    @RabbitListener(queues = "xj.course.process")
    public void onProcess(@Payload String payload, Channel channel, Message message) throws IOException {
        long tag = message.getMessageProperties().getDeliveryTag();
        Long courseId = null;
        try {
            cn.hutool.json.JSONObject json = cn.hutool.json.JSONUtil.parseObj(payload);
            courseId = json.getLong("courseId");
            String tempPath = json.getStr("tempPath", "");
            log.info("收到网课处理消息, courseId={}", courseId);
            coursePipelineService.process(courseId, tempPath);
            channel.basicAck(tag, false);
        } catch (Exception e) {
            log.error("网课处理消息消费失败, courseId={}", courseId, e);
            // 不重投：课程状态已由流水线标记为 FAILED，用户可手动重试
            channel.basicNack(tag, false, false);
        }
    }
}
