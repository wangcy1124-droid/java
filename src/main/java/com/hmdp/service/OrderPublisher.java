package com.hmdp.service;

import com.hmdp.config.OrderRabbitConfig;
import com.hmdp.dto.OrderMessage;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.util.concurrent.TimeUnit;

@Service
public class OrderPublisher {
    @Resource private RabbitTemplate rabbitTemplate;
    @Value("${hmdp.order.confirm-timeout-ms:3000}") private long timeoutMs;

    public void publish(OrderMessage message) throws Exception {
        CorrelationData correlation = new CorrelationData(message.getOrderId().toString());
        rabbitTemplate.convertAndSend(OrderRabbitConfig.EXCHANGE, OrderRabbitConfig.ROUTING, message, raw -> {
            raw.getMessageProperties().setDeliveryMode(MessageDeliveryMode.PERSISTENT);
            raw.getMessageProperties().setMessageId(message.getOrderId().toString());
            return raw;
        }, correlation);
        CorrelationData.Confirm confirm = correlation.getFuture().get(timeoutMs, TimeUnit.MILLISECONDS);
        if (!confirm.isAck() || correlation.getReturned() != null) {
            throw new IllegalStateException("消息未被正确路由或确认: " + confirm.getReason());
        }
    }
}
