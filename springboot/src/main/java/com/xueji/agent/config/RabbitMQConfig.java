package com.xueji.agent.config;

import com.xueji.agent.common.MqKeys;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.Queue;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * RabbitMQ 装配（PRD §16）：持久化交换机 / 队列，长耗时任务走 MQ
 */
@Configuration
public class RabbitMQConfig {

    @Bean
    public DirectExchange courseExchange() {
        return new DirectExchange(MqKeys.COURSE_EXCHANGE, true, false);
    }

    @Bean
    public Queue courseProcessQueue() {
        // durable 队列：服务重启消息不丢
        return new Queue(MqKeys.COURSE_PROCESS_QUEUE, true);
    }

    @Bean
    public Binding courseProcessBinding() {
        return BindingBuilder.bind(courseProcessQueue()).to(courseExchange()).with(MqKeys.COURSE_PROCESS_ROUTING);
    }
}
