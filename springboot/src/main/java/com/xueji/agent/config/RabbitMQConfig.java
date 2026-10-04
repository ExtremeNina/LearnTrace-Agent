package com.xueji.agent.config;

import com.xueji.agent.common.MqKeys;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.support.converter.DefaultJackson2JavaTypeMapper;
import org.springframework.amqp.support.converter.Jackson2JavaTypeMapper;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * RabbitMQ 装配（PRD §16）：持久化交换机 / 队列，长耗时任务走 MQ
 */
@Configuration
public class RabbitMQConfig {

    /**
     * 消息转换器：负载直接发对象，由 Jackson 序列化为 JSON，取代 SimpleMessageConverter
     * （后者对 Map / 对象走 JDK 序列化，Spring AMQP 3.x 消费端直接拒绝）。
     * 类型解析用 INFERRED 优先：按监听方法签名反序列化，不依赖 __TypeId__ 头的信任包校验。
     * 声明单个 MessageConverter Bean 后，Spring Boot 自动装配到 RabbitTemplate 与监听容器工厂。
     */
    @Bean
    public Jackson2JsonMessageConverter jacksonMessageConverter() {
        Jackson2JsonMessageConverter converter = new Jackson2JsonMessageConverter();
        DefaultJackson2JavaTypeMapper typeMapper = (DefaultJackson2JavaTypeMapper) converter.getJavaTypeMapper();
        typeMapper.setTypePrecedence(Jackson2JavaTypeMapper.TypePrecedence.INFERRED);
        return converter;
    }

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
