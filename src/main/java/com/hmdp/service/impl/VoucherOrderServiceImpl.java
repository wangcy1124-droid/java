package com.hmdp.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.dto.OrderMessage;
import com.hmdp.dto.Result;
import com.hmdp.entity.VoucherOrder;
import com.hmdp.enums.SeckillResult;
import com.hmdp.mapper.VoucherOrderMapper;
import com.hmdp.service.*;
import com.hmdp.utils.RedisIdWorker;
import com.hmdp.utils.UserHolder;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.util.UUID;

@Service
@Slf4j
public class VoucherOrderServiceImpl extends ServiceImpl<VoucherOrderMapper, VoucherOrder> implements IVoucherOrderService {
    @Resource private OrderReservationService reservations;
    @Resource private OrderPublisher publisher;
    @Resource private OrderCompensationService compensation;
    @Resource private RedisIdWorker redisIdWorker;

    @Override
    public Result seckillVoucher(Long voucherId) {
        if (voucherId == null || voucherId <= 0) return Result.fail("秒杀券 ID 不合法");
        OrderMessage message = new OrderMessage();
        message.setUserId(UserHolder.getUser().getId());
        message.setVoucherId(voucherId);
        message.setReservationId(UUID.randomUUID().toString().replace("-", ""));
        SeckillResult result = reservations.reserve(message);
        if (result != SeckillResult.SUCCESS) return Result.fail(result.getMessage());
        try {
            message.setOrderId(redisIdWorker.nextId("order"));
            if (!reservations.bind(message)) return Result.fail("抢购占用已失效，请重试");
            publisher.publish(message);
            return Result.ok(message.getOrderId());
        } catch (Exception failure) {
            log.error("订单发送未确认 orderId={} userId={} voucherId={} reservationId={}",
                    message.getOrderId(), message.getUserId(), voucherId, message.getReservationId(), failure);
            try {
                // 超时可能已消费成功：由同一数据库状态行协调取消和落库。
                if (compensation.cancel(message, "PUBLISH_FAILURE")) return Result.ok(message.getOrderId());
            } catch (RuntimeException retryLater) {
                log.error("补偿暂未完成，保留 Redis 占用记录待重试 orderId={} reservationId={}",
                        message.getOrderId(), message.getReservationId(), retryLater);
            } finally {
                if (failure instanceof InterruptedException) Thread.currentThread().interrupt();
            }
            return Result.fail("下单未确认，请稍后重试");
        }
    }
}
