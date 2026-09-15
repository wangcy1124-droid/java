package com.hmdp.service;

import com.hmdp.dto.OrderMessage;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import javax.annotation.Resource;

@Service
@Slf4j
public class OrderCompensationService {
    @Resource private OrderTransactionService transactions;
    @Resource private OrderReservationService reservations;

    public boolean cancel(OrderMessage message, String reason) {
        if (message.getOrderId() == null) {
            // 释放脚本确认仍未绑定订单号；与请求端 bind 原子竞争。
            reservations.release(message, false);
            return false;
        }
        OrderTransactionService.Outcome outcome = transactions.cancel(message);
        if (outcome == OrderTransactionService.Outcome.CREATED) {
            reservations.complete(message);
            log.info("订单已落库，不补偿 orderId={} userId={} voucherId={} reason={}",
                    message.getOrderId(), message.getUserId(), message.getVoucherId(), reason);
            return true;
        }
        reservations.release(message, outcome == OrderTransactionService.Outcome.CANCELLED_KEEP_QUALIFICATION);
        log.warn("订单取消并完成占用核对 orderId={} userId={} voucherId={} reservationId={} reason={}",
                message.getOrderId(), message.getUserId(), message.getVoucherId(), message.getReservationId(), reason);
        return false;
    }
}
