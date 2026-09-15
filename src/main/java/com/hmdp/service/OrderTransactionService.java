package com.hmdp.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.hmdp.dto.OrderMessage;
import com.hmdp.entity.OrderDelivery;
import com.hmdp.entity.SeckillVoucher;
import com.hmdp.entity.VoucherOrder;
import com.hmdp.enums.OrderStatus;
import org.springframework.beans.factory.annotation.Value;
import com.hmdp.mapper.OrderDeliveryMapper;
import com.hmdp.mapper.SeckillVoucherMapper;
import com.hmdp.mapper.VoucherOrderMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;

@Service
public class OrderTransactionService {
    public enum Outcome { CREATED, CANCELLED, CANCELLED_KEEP_QUALIFICATION }
    @Resource private OrderDeliveryMapper orderDeliveryMapper;
    @Resource private VoucherOrderMapper voucherOrderMapper;
    @Resource private SeckillVoucherMapper seckillVoucherMapper;
    @Resource private OrderReservationService reservations;
    @Value("${hmdp.order.payment-timeout-seconds:900}") private long paymentTimeoutSeconds;

    private OrderDelivery lock(OrderMessage message) {
        message.validate();
        orderDeliveryMapper.ensure(message);
        OrderDelivery delivery = orderDeliveryMapper.lock(message.getOrderId());
        if (!message.getUserId().equals(delivery.getUserId())
                || !message.getVoucherId().equals(delivery.getVoucherId())
                || !message.getReservationId().equals(delivery.getReservationId())) {
            throw new IllegalArgumentException("订单消息身份冲突 orderId=" + message.getOrderId());
        }
        return delivery;
    }

    @Transactional(rollbackFor = Exception.class)
    public Outcome create(OrderMessage message) {
        OrderDelivery delivery = lock(message);
        if ("CANCELLED".equals(delivery.getState())) return Outcome.CANCELLED;
        if ("CREATED".equals(delivery.getState())) return Outcome.CREATED;
        if (!reservations.isBound(message)) throw new IllegalStateException("订单没有对应的有效库存占用");
        if (seckillVoucherMapper.update(null, new UpdateWrapper<SeckillVoucher>()
                .eq("voucher_id", message.getVoucherId()).gt("stock", 0).setSql("stock=stock-1")) != 1) {
            throw new IllegalStateException("MySQL 库存不足 voucherId=" + message.getVoucherId());
        }
        VoucherOrder order = new VoucherOrder();
        order.setId(message.getOrderId());
        order.setUserId(message.getUserId());
        order.setVoucherId(message.getVoucherId());
        if (paymentTimeoutSeconds <= 0) throw new IllegalStateException("支付超时必须大于 0");
        order.setStatus(OrderStatus.PENDING.getCode());
        order.setVersion(0);
        order.setCreateTime(voucherOrderMapper.databaseTime());
        order.setExpireTime(order.getCreateTime().plusSeconds(paymentTimeoutSeconds));
        if (voucherOrderMapper.insert(order) != 1) throw new IllegalStateException("订单未保存");
        orderDeliveryMapper.state(message.getOrderId(), "CREATED");
        return Outcome.CREATED;
    }

    @Transactional(rollbackFor = Exception.class)
    public Outcome cancel(OrderMessage message) {
        OrderDelivery delivery = lock(message);
        VoucherOrder existing = voucherOrderMapper.selectById(message.getOrderId());
        if (existing != null) {
            if (!message.getUserId().equals(existing.getUserId()) || !message.getVoucherId().equals(existing.getVoucherId())) {
                throw new IllegalArgumentException("已落库订单与消息身份不符");
            }
            orderDeliveryMapper.state(message.getOrderId(), "CREATED");
            return Outcome.CREATED;
        }
        if ("CREATED".equals(delivery.getState())) throw new IllegalStateException("已创建订单记录丢失，禁止补偿");
        // 与 create() 锁同一行：提交 CANCELLED 后迟到消息不能再扣数据库库存。
        orderDeliveryMapper.state(message.getOrderId(), "CANCELLED");
        boolean otherOrder = voucherOrderMapper.selectCount(new QueryWrapper<VoucherOrder>()
                .eq("user_id", message.getUserId()).eq("voucher_id", message.getVoucherId())
                .ne("status", OrderStatus.CLOSED.getCode())) > 0;
        return otherOrder ? Outcome.CANCELLED_KEEP_QUALIFICATION : Outcome.CANCELLED;
    }
}
