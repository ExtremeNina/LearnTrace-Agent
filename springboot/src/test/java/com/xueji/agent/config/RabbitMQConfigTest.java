package com.xueji.agent.config;

import com.rabbitmq.client.Channel;
import com.xueji.agent.mq.CourseProcessConsumer;
import com.xueji.agent.mq.CourseProcessMessage;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import org.springframework.core.MethodParameter;

import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;

/**
 * MQ 消息转换器契约：对象负载序列化为 application/json，消费端按监听方法签名（INFERRED 优先，
 * 不依赖 __TypeId__ 头的信任包校验）还原为对象
 */
class RabbitMQConfigTest {

    @Test
    void converterShouldRoundTripObjectPayloadByListenerSignature() throws Exception {
        Jackson2JsonMessageConverter converter = new RabbitMQConfig().jacksonMessageConverter();

        CourseProcessMessage source = new CourseProcessMessage();
        source.setCourseId(15L);
        source.setTempPath("/tmp/a.mp4");
        Message message = converter.toMessage(source, new MessageProperties());
        assertEquals("application/json", message.getMessageProperties().getContentType());

        // 用真实监听方法的第 0 个参数类型作为转换提示（与 @RabbitListener 运行时行为一致）
        Method listenerMethod = CourseProcessConsumer.class.getMethod(
                "onProcess", CourseProcessMessage.class, Channel.class, Message.class);
        MethodParameter parameter = new MethodParameter(listenerMethod, 0);
        Object restored = converter.fromMessage(message, parameter);

        assertEquals(CourseProcessMessage.class, restored.getClass());
        CourseProcessMessage back = (CourseProcessMessage) restored;
        assertEquals(15L, back.getCourseId());
        assertEquals("/tmp/a.mp4", back.getTempPath());
    }

    @Test
    void rabbitTemplateShouldBindConverterExplicitly() {
        RabbitMQConfig config = new RabbitMQConfig();
        Jackson2JsonMessageConverter converter = config.jacksonMessageConverter();

        RabbitTemplate template = config.rabbitTemplate(mock(ConnectionFactory.class), converter);

        assertEquals(converter, template.getMessageConverter());
    }
}
