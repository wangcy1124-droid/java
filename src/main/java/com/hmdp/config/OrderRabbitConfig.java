package com.hmdp.config;

import org.springframework.amqp.core.*;
import org.springframework.amqp.rabbit.config.RetryInterceptorBuilder;
import org.springframework.amqp.rabbit.config.SimpleRabbitListenerContainerFactory;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.retry.RejectAndDontRequeueRecoverer;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import org.springframework.boot.autoconfigure.amqp.SimpleRabbitListenerContainerFactoryConfigurer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class OrderRabbitConfig {
    public static final String EXCHANGE = "seckill.order.exchange";
    public static final String ROUTING = "seckill.order.create";
    public static final String QUEUE = "seckill.order.queue";
    public static final String DLX = "seckill.order.dlx";
    public static final String DLQ = "seckill.order.dlq";
    public static final String PARKING = "seckill.order.parking";

    @Bean
    public Declarables orderTopology() {
        return new Declarables(
                new DirectExchange(EXCHANGE), new DirectExchange(DLX),
                QueueBuilder.durable(QUEUE).deadLetterExchange(DLX).deadLetterRoutingKey(DLQ).build(),
                QueueBuilder.durable(DLQ).deadLetterExchange(DLX).deadLetterRoutingKey(PARKING).build(),
                QueueBuilder.durable(PARKING).build(),
                new Binding(QUEUE, Binding.DestinationType.QUEUE, EXCHANGE, ROUTING, null),
                new Binding(DLQ, Binding.DestinationType.QUEUE, DLX, DLQ, null),
                new Binding(PARKING, Binding.DestinationType.QUEUE, DLX, PARKING, null));
    }

    @Bean
    public Jackson2JsonMessageConverter orderMessageConverter() {
        return new Jackson2JsonMessageConverter();
    }

    @Bean
    public SimpleRabbitListenerContainerFactory rabbitListenerContainerFactory(
            SimpleRabbitListenerContainerFactoryConfigurer configurer, ConnectionFactory connectionFactory) {
        SimpleRabbitListenerContainerFactory factory = new SimpleRabbitListenerContainerFactory();
        configurer.configure(factory, connectionFactory);
        // AUTO 是监听方法正常返回后 ACK；业务事务在独立 Service 代理中已提交。
        factory.setAcknowledgeMode(AcknowledgeMode.AUTO);
        factory.setDefaultRequeueRejected(false);
        org.springframework.amqp.rabbit.listener.ConditionalRejectingErrorHandler errorHandler =
                new org.springframework.amqp.rabbit.listener.ConditionalRejectingErrorHandler();
        errorHandler.setDiscardFatalsWithXDeath(false);
        factory.setErrorHandler(errorHandler);
        factory.setAdviceChain(RetryInterceptorBuilder.stateless().maxAttempts(3)
                .backOffOptions(200, 2, 1000)
                .recoverer(new RejectAndDontRequeueRecoverer()).build());
        return factory;
    }
}
