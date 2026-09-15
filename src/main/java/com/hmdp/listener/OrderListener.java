package com.hmdp.listener;

import com.hmdp.config.OrderRabbitConfig;
import com.hmdp.dto.OrderMessage;
import com.hmdp.service.OrderCompensationService;
import com.hmdp.service.OrderReservationService;
import com.hmdp.service.OrderTransactionService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;
import javax.annotation.Resource;

@Component
@Slf4j
public class OrderListener {
    @Resource private OrderTransactionService transactions;
    @Resource private OrderReservationService reservations;
    @Resource private OrderCompensationService compensation;

    @RabbitListener(id = "orderCreate", queues = OrderRabbitConfig.QUEUE)
    public void create(OrderMessage message) {
        message.validate();
        try {
            OrderTransactionService.Outcome outcome = transactions.create(message);
            // 这里事务代理已经提交；Redis 清理失败让消息重试，DB 处理保持幂等。
            if (outcome == OrderTransactionService.Outcome.CREATED) reservations.complete(message);
            else compensation.cancel(message, "LATE_MESSAGE");
        } catch (RuntimeException e) {
            log.warn("订单消费失败，交给有限重试 orderId={} userId={} voucherId={} cause={}",
                    message.getOrderId(), message.getUserId(), message.getVoucherId(), e.toString());
            throw e;
        }
    }

    @RabbitListener(id = "orderDeadLetter", queues = OrderRabbitConfig.DLQ)
    public void deadLetter(OrderMessage message) {
        message.validate();
        compensation.cancel(message, "DLQ");
        // 正常返回才 ACK；补偿失败三次后路由 parking，保留人工排查消息。
    }
}
