package com.xueji.agent.config;

import com.xueji.agent.common.MqKeys;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.support.converter.DefaultJackson2JavaTypeMapper;
import org.springframework.amqp.support.converter.Jackson2JavaTypeMapper;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * RabbitMQ 装配（PRD §16）：持久化交换机 / 队列，长耗时任务走 MQ
 */
@Slf4j
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

    /**
     * 显式声明 RabbitTemplate（Boot 自动装配退位）：绑定上方转换器 + 发送可靠性三件套，
     * 防止消息在「发送端 → 交换机 → 队列」途中静默丢失。
     * 两回调生效依赖 yml 的 publisher-confirm-type: correlated 与 publisher-returns: true，漏配则静默失效。
     */
    @Bean
    public RabbitTemplate rabbitTemplate(ConnectionFactory connectionFactory,
                                         Jackson2JsonMessageConverter jacksonMessageConverter) {
        RabbitTemplate rabbitTemplate = new RabbitTemplate(connectionFactory);
        rabbitTemplate.setMessageConverter(jacksonMessageConverter);

        // 消息未到达交换机（publisher-confirm-type: correlated 才会触发）
        rabbitTemplate.setConfirmCallback((correlationData, ack, cause) -> {
            if (!ack) {
                log.error("消息发送未到达交换机, correlationData={}, cause={}", correlationData, cause);
            }
        });

        // 不可路由到队列的消息退回而非静默丢弃（publisher-returns: true 且 mandatory=true 才会触发）
        rabbitTemplate.setMandatory(true);
        rabbitTemplate.setReturnsCallback(returned ->
                log.error("消息无法路由到队列, exchange={}, routingKey={}, replyText={}, message={}",
                        returned.getExchange(), returned.getRoutingKey(), returned.getReplyText(), returned.getMessage()));

        return rabbitTemplate;
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
